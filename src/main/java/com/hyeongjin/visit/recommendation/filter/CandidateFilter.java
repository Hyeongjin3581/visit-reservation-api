package com.hyeongjin.visit.recommendation.filter;

import com.hyeongjin.visit.recommendation.domain.CaregiverSchedule;
import com.hyeongjin.visit.request.domain.VisitRequest;

// 추천 필수 조건. 하나라도 false 면 후보에서 제외
// 새 조건이 생기면 구현체를 @Component 로 추가하면 서비스 코드는 그대로 둠
public interface CandidateFilter {

    boolean accept(CaregiverSchedule schedule, VisitRequest request);
}
