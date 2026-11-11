// 추천 API 부하 테스트: 동시 사용자 50명이 30초 동안 같은 방문 요청의 추천을 계속 호출
// 준비: 샘플 데이터를 넣은 앱 실행 (./gradlew bootRun --args='--spring.profiles.active=seed')
// 실행: k6 run k6/recommendation.js
// 바꾸기: k6 run -e VUS=100 -e DURATION=1m -e BASE_URL=http://EC2주소:8080 k6/recommendation.js
// 결과: 요약의 http_req_duration{name:recommend} 줄의 p(95)
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  vus: Number(__ENV.VUS || 50),
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 임계값을 걸어 두면 요약에 이 요청만 따로 집계된 줄이 나옴
    'http_req_duration{name:recommend}': ['p(95)<2000'],
  },
};

// 테스트 시작 전에 한 번: 수급자와 방문 요청(이틀 뒤 10:00~12:00)을 만듦
export function setup() {
  const recipient = http.post(`${BASE_URL}/api/recipients`, JSON.stringify({
    name: 'k6수급자', preferredGender: null, latitude: 37.5665, longitude: 126.978,
  }), JSON_HEADERS);
  check(recipient, { '수급자 등록 201': (r) => r.status === 201 });

  const request = http.post(`${BASE_URL}/api/visit-requests`, JSON.stringify({
    recipientId: recipient.json('id'), visitDate: daysLater(2), startTime: '10:00', endTime: '12:00',
  }), JSON_HEADERS);
  check(request, { '방문 요청 등록 201': (r) => r.status === 201 });

  return { visitRequestId: request.json('id') };
}

export default function (data) {
  const res = http.get(
    `${BASE_URL}/api/visit-requests/${data.visitRequestId}/recommendations?size=3`,
    { tags: { name: 'recommend' } },
  );
  check(res, {
    '200': (r) => r.status === 200,
    '3명 추천': (r) => r.status === 200 && r.json('candidates').length === 3,
  });
}

// 서버(한국 시간)와 날짜가 어긋나도 미래 날짜가 되도록 넉넉하게 이틀 뒤부터 사용
function daysLater(days) {
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
}
