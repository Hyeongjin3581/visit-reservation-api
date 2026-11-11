// 예약 확정 부하 테스트: 동시 사용자 50명이 30초 동안 "방문 요청 등록 → 보호사 20명 중 한 명으로 확정"을 반복
// 보호사가 20명뿐이라 같은 보호사·겹치는 시간 요청이 자주 부딪힘 → 락 전략별 처리량과 대기 시간 비교
// 실행 (락 전략마다 앱을 다시 띄워서 반복):
//   $env:RESERVATION_LOCK_STRATEGY="pessimistic"; ./gradlew bootRun     (다른 창에서)
//   k6 run -e STRATEGY=pessimistic k6/reservation.js
// redisson 은 Redis 를 띄우고 $env:REDIS_ENABLED="true" 도 함께 설정
// 결과: http_req_duration{name:confirm} 의 p(95), http_reqs{name:confirm} 의 /s(처리량),
//       reservations_confirmed / reservations_conflicted (성공·충돌 건수)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CAREGIVERS = Number(__ENV.CAREGIVERS || 20);
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };
const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

const confirmed = new Counter('reservations_confirmed');
const conflicted = new Counter('reservations_conflicted');

// 409(충돌)는 정상적인 거절이므로 실패로 세지 않음
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  vus: Number(__ENV.VUS || 50),
  duration: __ENV.DURATION || '30s',
  setupTimeout: '2m',
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'max'],
  tags: { strategy: __ENV.STRATEGY || 'unknown' },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:confirm}': ['p(95)<5000'],
    'http_reqs{name:confirm}': ['count>0'],
  },
};

// 테스트 시작 전에 한 번: 수급자 1명, 모든 요일 09~18시 근무하는 보호사 CAREGIVERS 명
export function setup() {
  const recipient = http.post(`${BASE_URL}/api/recipients`, JSON.stringify({
    name: 'k6수급자', preferredGender: null, latitude: 37.5665, longitude: 126.978,
  }), JSON_HEADERS);
  check(recipient, { '수급자 등록 201': (r) => r.status === 201 });

  const caregiverIds = [];
  for (let i = 0; i < CAREGIVERS; i++) {
    const caregiver = http.post(`${BASE_URL}/api/caregivers`, JSON.stringify({
      name: `k6보호사${i}`, gender: i % 2 === 0 ? 'FEMALE' : 'MALE', latitude: 37.57, longitude: 126.98,
    }), JSON_HEADERS);
    check(caregiver, { '보호사 등록 201': (r) => r.status === 201 });
    const id = caregiver.json('id');
    for (const day of DAYS) {
      http.post(`${BASE_URL}/api/caregivers/${id}/availabilities`, JSON.stringify({
        dayOfWeek: day, startTime: '09:00', endTime: '18:00',
      }), JSON_HEADERS);
    }
    caregiverIds.push(id);
  }
  return { recipientId: recipient.json('id'), caregiverIds };
}

export default function (data) {
  // 2~8일 뒤, 09:00~16:00 사이 30분 단위 시작, 1시간짜리 방문
  const date = daysLater(2 + Math.floor(Math.random() * 7));
  const startMinutes = 9 * 60 + 30 * Math.floor(Math.random() * 15);
  const request = http.post(`${BASE_URL}/api/visit-requests`, JSON.stringify({
    recipientId: data.recipientId, visitDate: date,
    startTime: hhmm(startMinutes), endTime: hhmm(startMinutes + 60),
  }), Object.assign({ tags: { name: 'create-request' } }, JSON_HEADERS));
  if (request.status !== 201) {
    return;
  }

  const caregiverId = data.caregiverIds[Math.floor(Math.random() * data.caregiverIds.length)];
  const res = http.post(
    `${BASE_URL}/api/visit-requests/${request.json('id')}/reservations`,
    JSON.stringify({ caregiverId }),
    Object.assign({ tags: { name: 'confirm' } }, JSON_HEADERS),
  );
  if (res.status === 201) {
    confirmed.add(1);
  } else if (res.status === 409) {
    conflicted.add(1);
  }
  check(res, { '201 또는 409': (r) => r.status === 201 || r.status === 409 });
}

function hhmm(minutes) {
  const h = String(Math.floor(minutes / 60)).padStart(2, '0');
  const m = String(minutes % 60).padStart(2, '0');
  return `${h}:${m}`;
}

function daysLater(days) {
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
}
