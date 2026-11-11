package com.hyeongjin.visit.recommendation.score;

import com.hyeongjin.visit.recommendation.domain.Candidate;

// 점수 항목 하나. 항목별 만점의 합이 100점이 되도록 맞춤 (ScorePolicyTest 에서 확인)
// 온케어에서는 점수 계산이 서비스 클래스 하나에 모여 있어서, 항목을 바꾸면 서비스 전체를 고쳐야 했음
public interface ScorePolicy {

    // 응답의 scores 에 들어갈 이름
    String name();

    double maxScore();

    double score(Candidate candidate);
}
