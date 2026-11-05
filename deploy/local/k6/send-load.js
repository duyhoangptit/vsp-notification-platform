// Load test for POST /api/v1/notifications/send (quickstart Q9; SC-001, SC-005, SC-014).
// Usage: k6 run deploy/local/k6/send-load.js -e RATE=500 -e DURATION=5m [-e PNH=http://localhost:8080]
//        [-e CLIENT=demo-client -e SECRET=demo-secret]
import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const PNH = __ENV.PNH || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 500);
const DURATION = __ENV.DURATION || '5m';
const AUTH = 'Basic ' + encoding.b64encode(`${__ENV.CLIENT || 'demo-client'}:${__ENV.SECRET || 'demo-secret'}`);

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 4),
    },
  },
  thresholds: {
    'http_req_duration{name:send}': ['p(95)<1000'], // SC-001: p95 acknowledgement < 1 s
    http_req_failed: ['rate<0.001'], // SC-005: sustained without errors
    dropped_iterations: ['count<' + Math.ceil(RATE * 0.01)], // the target rate is actually sustained
  },
};

// 80 % EMAIL (SC-014 partition balance check), 15 % SMS, 5 % PUSH; 10 000 distinct users → keys spread over partitions
function payload() {
  const r = Math.random();
  const user = `load_user_${Math.floor(Math.random() * 10000)}`;
  const base = { client_request_id: `k6-${uuidv4()}`, user_id: user };
  if (r < 0.8) {
    return { ...base, channel_type: 'EMAIL', recipient_target: `${user}@example.com`, template_code: 'NEWSLETTER_EMAIL', variables: { name: user } };
  }
  if (r < 0.95) {
    return { ...base, channel_type: 'SMS', recipient_target: '+84901234567', content: { body: 'k6 load message' } };
  }
  return { ...base, channel_type: 'PUSH', recipient_target: `token-${user}`, template_code: 'ORDER_SHIPPED_PUSH', variables: { order_id: '42' } };
}

export default function () {
  const res = http.post(`${PNH}/api/v1/notifications/send`, JSON.stringify(payload()), {
    headers: { 'Content-Type': 'application/json', Authorization: AUTH },
    tags: { name: 'send' },
  });
  check(res, { accepted: (r) => r.status === 202 || r.status === 200 });
}
