package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.adapter.out.persistence.*
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.vo.ProjectId
import com.dochiri.ilsim.task.domain.vo.TaskId
import java.lang.reflect.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

class JpaTaskStoreTransactionTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> repository(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> error("저장소 호출 전 DB 오류가 발생해야 합니다") } as T

    @Test
    fun `클래스 프록시를 만들 수 있고 결과 저장 오류는 트랜잭션을 롤백한다`() {
        var begins = 0; var rollbacks = 0
        val manager = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) { begins++ }
            override fun doCommit(status: DefaultTransactionStatus) { error("실패한 저장은 commit할 수 없습니다") }
            override fun doRollback(status: DefaultTransactionStatus) { rollbacks++ }
        }
        val target = JpaTaskStore(TaskSchema(JdbcTemplate()), repository(TaskJpaRepository::class.java), repository(TaskEventJpaRepository::class.java), repository(ProjectJpaRepository::class.java))
        val advice = TransactionInterceptor().apply {
            transactionManager = manager
            transactionAttributeSource = AnnotationTransactionAttributeSource()
            afterPropertiesSet()
        }
        val proxy = ProxyFactory(target).apply { isProxyTargetClass = true; addAdvice(advice) }.proxy as TaskStore
        assertThat(AopUtils.isCglibProxy(proxy)).isTrue()
        assertThatThrownBy { proxy.loadTasks() }.isInstanceOf(IllegalStateException::class.java).hasMessage("No DataSource set")
        assertThat(begins).isZero()
        val task = Task.create(TaskId.of("task"), MutableClock().instant(), ProjectId.of("demo"), null, "기능")
        assertThatThrownBy { proxy.saveOutcome(task, emptyList()) }.isInstanceOf(RuntimeException::class.java)
        assertThat(begins).isEqualTo(1)
        assertThat(rollbacks).isEqualTo(1)
    }
}
