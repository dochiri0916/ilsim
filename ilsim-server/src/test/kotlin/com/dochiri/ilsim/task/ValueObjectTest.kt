package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.vo.Attempt
import com.dochiri.ilsim.task.domain.vo.Branch
import com.dochiri.ilsim.task.domain.vo.Feature
import com.dochiri.ilsim.task.domain.vo.ProjectId
import com.dochiri.ilsim.task.domain.vo.RunCount
import com.dochiri.ilsim.task.domain.vo.Requirement
import com.dochiri.ilsim.task.domain.vo.RunnerId
import com.dochiri.ilsim.task.domain.vo.TaskId
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ValueObjectTest {

    @Test
    fun `기능 이름은 소문자 숫자 하이픈만 받고 없으면 작업 id 앞 8자로 정한다`() {
        assertThatThrownBy { Feature.of("Bad_Name") }.isInstanceOf(InvalidTaskRequestException::class.java)
        assertThat(Feature.of("slugify-2").value).isEqualTo("slugify-2")
        assertThat(Feature.defaultFor(TaskId.of("0123456789abcdef")).value).isEqualTo("feat-01234567")
    }

    @Test
    fun `요구사항과 브랜치는 빈 값을 거절한다`() {
        assertThatThrownBy { Requirement.of(null) }.isInstanceOf(InvalidTaskRequestException::class.java)
        assertThatThrownBy { Requirement.of("   ") }.isInstanceOf(InvalidTaskRequestException::class.java)
        assertThatThrownBy { Branch.of("") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `실행 횟수와 시도 번호는 범위를 벗어나면 만들 수 없고 증가는 새 값을 준다`() {
        assertThatThrownBy { RunCount.of(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Attempt.of(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(RunCount.ZERO.increment().value).isEqualTo(1)
        assertThat(Attempt.FIRST.next().value).isEqualTo(2)
        assertThat(RunCount.ZERO.value).isZero()
    }

    @Test
    fun `식별자 값 객체는 같은 문자열이면 같다`() {
        assertThat(RunnerId.of("r1")).isEqualTo(RunnerId.of("r1"))
        assertThat(ProjectId.of("demo")).isNotEqualTo(ProjectId.of("other"))
    }

    @Test
    fun `작업은 상태를 바꿔도 원래 인스턴스는 그대로다`() {
        val created = Task.create(TaskId.of("t-1"), MutableClock().now, ProjectId.of("demo"), "slug", "요구사항")
        val assigned = created.assign(RunnerId.of("r1"))

        assertThat(created.status).isEqualTo(TaskStatus.ACTIVE)
        assertThat(created.runs.value).isZero()
        assertThat(created.runnerId).isNull()
        assertThat(assigned.runs.value).isEqualTo(1)
        assertThat(assigned.runnerId?.value).isEqualTo("r1")
    }
}
