// Flash sale load test for one event.
//
// Profiles (PROFILE env var):
//   sellout (default) far more buyers than tickets, above what the local emulator can absorb. Checks
//                     correctness under overload: no overselling, every ticket ends up held, every held
//                     ticket was reported to its buyer, and a retried request returns the same order.
//   steady            a rate the local emulator can absorb, with more tickets than buyers. Measures latency.
//
// Run (API on localhost:8080). k6 sends everything from one address, so start the stack with the per-address
// limit off: RATE_LIMIT_IP_RPS=0 docker compose up -d --wait
//   docker run --rm --network host -v "$PWD/load-test:/scripts" grafana/k6:2.3.0 run /scripts/flash-sale.js
//   docker run --rm --network host -e PROFILE=steady -v "$PWD/load-test:/scripts" grafana/k6:2.3.0 run /scripts/flash-sale.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const PROFILES = {
    sellout: { capacity: 1000, rate: 300, duration: '60s' },
    steady: { capacity: 100000, rate: 40, duration: '60s' },
};
const PROFILE = __ENV.PROFILE || 'sellout';
const SELLOUT = PROFILE === 'sellout';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const CAPACITY = Number(__ENV.CAPACITY || PROFILES[PROFILE].capacity);
const RATE = Number(__ENV.RATE || PROFILES[PROFILE].rate);
const DURATION = __ENV.DURATION || PROFILES[PROFILE].duration;
const MAX_ATTEMPTS = 6;

const accepted = new Counter('orders_accepted');
const ticketsHeld = new Counter('tickets_held');
const soldOut = new Counter('orders_sold_out');
const gaveUp = new Counter('orders_gave_up');
const clientRetries = new Counter('client_retries');
const shed = new Counter('requests_shed');
const replayMismatch = new Counter('idempotent_replay_mismatch');

// 409 SOLD_OUT, 429 RATE_LIMITED and 503 (OVERLOADED, CONTENTION, TIMEOUT) are expected answers in a flash
// sale, not failures.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409, 429, 503));
const RETRYABLE = [429, 503];

const thresholds = {
    'http_req_failed': ['rate<0.01'],
    'idempotent_replay_mismatch': ['count==0'],
    'checks': ['rate>0.99'],
};
if (SELLOUT) {
    // Every ticket the datastore holds must have been reported to the buyer that owns it.
    thresholds['tickets_held'] = [`count==${CAPACITY}`];
} else {
    thresholds['http_req_duration{name:place_order}'] = ['p(95)<200', 'p(99)<500'];
    thresholds['orders_gave_up'] = ['count==0'];
}

export const options = {
    scenarios: {
        buyers: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 100,
            maxVUs: 400,
        },
    },
    thresholds,
    teardownTimeout: '90s',
};

const json = { 'Content-Type': 'application/json' };

// A well-behaved client: on 429/503 it waits Retry-After and sends the same request with the same key.
function placeOrder(body, params) {
    let res;
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        res = http.post(`${BASE_URL}/orders`, body, params);
        if (!RETRYABLE.includes(res.status)) {
            return res;
        }
        clientRetries.add(1);
        // Rejected at the edge: certainly not written, unlike a TIMEOUT.
        if (res.status === 429 || (res.status === 503 && res.json('code') === 'OVERLOADED')) {
            shed.add(1);
        }
        sleep(Number(res.headers['Retry-After'] || 1));
    }
    return res;
}

export function setup() {
    const res = http.post(`${BASE_URL}/events`, JSON.stringify({
        name: `Flash sale load test (${PROFILE})`,
        venue: 'Load test arena',
        date: '2027-06-01T20:00:00Z',
        capacity: CAPACITY,
        ticketPrice: 150000,
        currency: 'COP',
    }), { headers: { ...json, 'X-User-Role': 'admin', 'Idempotency-Key': `load-${Date.now()}` } });
    check(res, { 'event created': (r) => r.status === 201 });
    return { eventId: res.json('id') };
}

export default function (data) {
    const iteration = exec.scenario.iterationInTest;
    const quantity = (iteration % 2) + 1;
    const params = {
        headers: { ...json, 'X-User-Id': `buyer-${iteration}`, 'Idempotency-Key': `order-${iteration}` },
        tags: { name: 'place_order' },
    };
    const body = JSON.stringify({ eventId: data.eventId, quantity });

    const res = placeOrder(body, params);
    check(res, { 'expected status': (r) => [202, 409, 429, 503].includes(r.status) });

    if (res.status === 202) {
        accepted.add(1);
        ticketsHeld.add(quantity);
        // One in ten buyers sends the same request again, as a client would after a lost response.
        if (iteration % 10 === 0) {
            const retry = placeOrder(body, { ...params, tags: { name: 'retry_order' } });
            // Turned away at the edge is fine (nothing written); any other answer must be the same order.
            const sameOrder = retry.status === 202 && retry.json('orderId') === res.json('orderId');
            if (!sameOrder && !RETRYABLE.includes(retry.status)) {
                replayMismatch.add(1);
            }
        }
    } else if (res.status === 409) {
        soldOut.add(1);
    } else if (RETRYABLE.includes(res.status)) {
        gaveUp.add(1);
    }
}

export function teardown(data) {
    // Wait for the worker to drain the queue: every held order should leave RESERVED.
    let a;
    for (let i = 0; i < 60; i++) {
        a = http.get(`${BASE_URL}/events/${data.eventId}/availability`).json();
        if (a.reserved === 0) {
            break;
        }
        sleep(1);
    }
    console.log(`availability: ${JSON.stringify(a)}`);
    check(a, {
        'no overselling (counters add up to capacity)':
            (v) => v.available + v.reserved + v.pendingConfirmation + v.sold + v.complimentary === v.total,
        'queue drained (nothing left in RESERVED)': (v) => v.reserved === 0,
    });
    if (SELLOUT) {
        check(a, { 'every ticket held': (v) => v.available === 0 });
    }
}
