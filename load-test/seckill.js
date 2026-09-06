import http from 'k6/http';
import { check } from 'k6';

// TOKENS 使用逗号分隔。每个 VU 固定使用一个登录用户，才能同时验证一人一单和限流。
// 示例：k6 run -e VOUCHER_ID=1 -e TOKENS=token1,token2 load-test/seckill.js
export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '10s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<500'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8081';
const voucherId = __ENV.VOUCHER_ID || '1';
const tokens = (__ENV.TOKENS || '').split(',').filter(Boolean);

export default function () {
  if (tokens.length === 0) {
    throw new Error('请通过 TOKENS 提供至少一个登录 token');
  }
  const token = tokens[(__VU - 1) % tokens.length];
  const response = http.post(
    `${baseUrl}/voucher-order/seckill/${voucherId}`,
    null,
    { headers: { authorization: token } },
  );
  check(response, {
    'request handled': (r) => r.status === 200 || r.status === 429,
  });
}
