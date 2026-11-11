package com.hyeongjin.visit.recommendation.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recommendation.domain.Candidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 점수 정책 경계값 단위 테스트 (스프링 없이 실행)
class ScorePolicyTest {

    private final DistanceScorePolicy distance = new DistanceScorePolicy();
    private final WorkloadScorePolicy workload = new WorkloadScorePolicy();
    private final ContinuityScorePolicy continuity = new ContinuityScorePolicy();

    @Test
    void 항목별_만점의_합은_100점() {
        double sum = List.of(distance, workload, continuity).stream().mapToDouble(ScorePolicy::maxScore).sum();
        assertThat(sum).isEqualTo(100.0);
    }

    @ParameterizedTest(name = "{0}km → {1}점")
    @CsvSource({"0, 50", "12.5, 25", "25, 0", "40, 0"})
    void 거리_점수는_25km까지_직선으로_줄어든다(double km, double expected) {
        assertThat(distance.score(candidate(km, 0, 0))).isCloseTo(expected, within(1e-9));
    }

    @ParameterizedTest(name = "최근 30일 {0}회 → {1}점")
    @CsvSource({"0, 30", "10, 15", "20, 0", "35, 0"})
    void 근무량_점수는_20회까지_직선으로_줄어든다(long count, double expected) {
        assertThat(workload.score(candidate(0, count, 0))).isCloseTo(expected, within(1e-9));
    }

    @ParameterizedTest(name = "같은 수급자 {0}회 → {1}점")
    @CsvSource({"0, 0", "1, 5", "3, 15", "4, 20", "9, 20"})
    void 연속_담당_점수는_1회당_5점_최대_20점(long count, double expected) {
        assertThat(continuity.score(candidate(0, 0, count))).isEqualTo(expected);
    }

    private Candidate candidate(double distanceKm, long recentWorkCount, long continuityCount) {
        Caregiver caregiver = Caregiver.create("테스트", Gender.FEMALE, 37.5, 127.0);
        return new Candidate(caregiver, distanceKm, recentWorkCount, continuityCount);
    }
}
