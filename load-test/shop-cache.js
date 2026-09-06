import http from 'k6/http';
import { check } from 'k6';

// 示例：k6 run -e BASE_URL=http://localhost:8081 -e SHOP_ID=1 load-test/shop-cache.js
// 先在自己的机器上跑基线，再逐级提高 RATE；不要把未经测试的 QPS 写进简历。
export const options = {
  scenarios: {
    cachedShop: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 100),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Number(__ENV.VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 500),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8081';
const shopId = __ENV.SHOP_ID || '1';

export default function () {
  const response = http.get(`${baseUrl}/shop/${shopId}`);
  check(response, {
    'status is 200': (r) => r.status === 200,
    'business result succeeds': (r) => r.json('success') === true,
  });
}
