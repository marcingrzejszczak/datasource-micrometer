package net.ttddyy.observation.tracing;

import java.lang.reflect.Method;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleTracer;
import net.ttddyy.dsproxy.ConnectionInfo;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.observation.tracing.ConnectionAttributesManager.ConnectionAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.micrometer.tracing.test.simple.SpanAssert.assertThat;
import static io.micrometer.tracing.test.simple.TracerAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link QueryTracingExecutionListener}.
 *
 * @author Tadaya Tsuyukubo
 */
class QueryTracingExecutionListenerTests {

	private SimpleTracer tracer;

	private ObservationRegistry registry;

	@BeforeEach
	void setup() {
		this.tracer = new SimpleTracer();
		this.registry = ObservationRegistry.create();
	}

	@Test
	void query() throws Exception {
		this.registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(this.tracer));
		QueryTracingExecutionListener listener = new QueryTracingExecutionListener(this.registry);

		Method execute = Statement.class.getMethod("execute", String.class);

		QueryInfo queryInfo = new QueryInfo();
		queryInfo.setQuery("SELECT 1");

		ExecutionInfo executionInfo = new ExecutionInfo();
		executionInfo.setConnectionId("id-1");
		executionInfo.setDataSourceName("myDS");
		executionInfo.setMethod(execute);
		List<QueryInfo> queryInfos = Arrays.asList(queryInfo);

		listener.beforeQuery(executionInfo, queryInfos);
		assertThat(tracer.currentSpan()).isNotNull();
		listener.afterQuery(executionInfo, queryInfos);
		assertThat(tracer.currentSpan()).isNull();

		assertThat(tracer)
				.onlySpan()
				.hasNameEqualTo("query")
				.hasTag("jdbc.query[0]", "SELECT 1")
				.doesNotHaveTagWithKey("jdbc.row-count")
		;
	}

	@Test
	void queryConnectionContext() throws Exception {
		this.registry.observationConfig().observationHandler(new QueryTracingObservationHandler(this.tracer));
		QueryTracingExecutionListener listener = new QueryTracingExecutionListener(this.registry);

		ConnectionInfo connectionInfo = new ConnectionInfo();
		connectionInfo.setDataSourceName("myDS");

		ConnectionAttributes connectionAttributes = new ConnectionAttributes();
		connectionAttributes.connectionInfo = connectionInfo;
		connectionAttributes.host = "localhost";
		connectionAttributes.port = 5555;
		ConnectionAttributesManager connectionAttributesManager = mock(ConnectionAttributesManager.class);
		given(connectionAttributesManager.get("id-1")).willReturn(connectionAttributes);
		listener.setConnectionAttributesManager(connectionAttributesManager);

		Method execute = Statement.class.getMethod("execute", String.class);

		ExecutionInfo executionInfo = new ExecutionInfo();
		executionInfo.setConnectionId("id-1");
		executionInfo.setDataSourceName("myDS");
		executionInfo.setMethod(execute);
		List<QueryInfo> queryInfos = new ArrayList<>();

		listener.beforeQuery(executionInfo, queryInfos);
		listener.afterQuery(executionInfo, queryInfos);

		assertThat(tracer)
				.onlySpan()
				.hasRemoteServiceNameEqualTo("myDS")
				.hasIpEqualTo("localhost")
				.hasPortEqualTo(5555)
		;
	}

	@Test
	void queryRowCount() throws Exception {
		this.registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(this.tracer));
		QueryTracingExecutionListener listener = new QueryTracingExecutionListener(this.registry);

		Method executeUpdate = Statement.class.getMethod("executeUpdate", String.class);

		ExecutionInfo executionInfo = new ExecutionInfo();
		executionInfo.setConnectionId("id-1");
		executionInfo.setDataSourceName("myDS");
		executionInfo.setMethod(executeUpdate);
		executionInfo.setResult(99);
		List<QueryInfo> queryInfos = new ArrayList<>();

		listener.beforeQuery(executionInfo, queryInfos);
		listener.afterQuery(executionInfo, queryInfos);

		assertThat(tracer)
				.onlySpan()
				.hasTag("jdbc.row-count", "99");
	}

	@Test
	void connection() throws Exception {
		this.registry.observationConfig().observationHandler(new ConnectionTracingObservationHandler(this.tracer));

		QueryTracingExecutionListener listener = new QueryTracingExecutionListener(this.registry);

		Method getConnection = DataSource.class.getMethod("getConnection");
		Method closeMethod = Connection.class.getMethod("close");

		DatabaseMetaData metaData = mock(DatabaseMetaData.class);
		Connection connection = mock(Connection.class);
		given(connection.getMetaData()).willReturn(metaData);
		given(metaData.getURL()).willReturn("jdbc:mysql://localhost:5555/mydatabase");

		ConnectionInfo connectionInfo = new ConnectionInfo();
		connectionInfo.setConnectionId("id-1");
		connectionInfo.setDataSourceName("myDS");

		MethodExecutionContext executionContext = new MethodExecutionContext();
		executionContext.setConnectionInfo(connectionInfo);
		executionContext.setMethod(getConnection);
		executionContext.setTarget(mock(DataSource.class));
		executionContext.setResult(connection);

		listener.beforeMethod(executionContext);
		assertThat(tracer.currentSpan()).isNotNull();
		listener.afterMethod(executionContext);
		assertThat(tracer.currentSpan()).isNotNull();

		// "getConnection" starts a span but it will not end until "Connection#close".
		assertThat(tracer.currentSpan()).isNotEnded();
		assertThat(tracer.getSpans()).hasSize(1);

		// "Connection#close()" will close the span
		MethodExecutionContext secondExecutionContext = new MethodExecutionContext();
		secondExecutionContext.setConnectionInfo(connectionInfo);
		secondExecutionContext.setMethod(closeMethod);
		secondExecutionContext.setTarget(mock(Connection.class));

		listener.beforeMethod(secondExecutionContext);
		assertThat(tracer.currentSpan()).isNotNull();
		listener.afterMethod(secondExecutionContext);
		assertThat(tracer.currentSpan()).isNull();

		assertThat(this.tracer)
				.onlySpan()
				.hasNameEqualTo("connection")
				.hasRemoteServiceNameEqualTo("myDS")
				.hasIpEqualTo("localhost")
				.hasPortEqualTo(5555)
		;
	}

	@Test
	void commitAndRollback() throws Exception {
		Method commitMethod = Connection.class.getMethod("commit");
		Method rollbackMethod = Connection.class.getMethod("rollback");

		ConnectionContext connectionContext = new ConnectionContext();
		ConnectionAttributes connectionAttributes = new ConnectionAttributes();
		ConnectionAttributesManager connectionAttributesManager = new DefaultConnectionAttributesManager();
		connectionAttributes.connectionContext = connectionContext;
		connectionAttributesManager.put("id-1", connectionAttributes);

		QueryTracingExecutionListener listener = new QueryTracingExecutionListener(this.registry);
		listener.setConnectionAttributesManager(connectionAttributesManager);

		ConnectionInfo connectionInfo = new ConnectionInfo();
		connectionInfo.setConnectionId("id-1");

		MethodExecutionContext commitExecutionContext = new MethodExecutionContext();
		commitExecutionContext.setConnectionInfo(connectionInfo);
		commitExecutionContext.setMethod(commitMethod);
		commitExecutionContext.setTarget(mock(Connection.class));

		listener.afterMethod(commitExecutionContext);

		assertThat(connectionContext.getCommitAt()).isNotNull();

		// check rollback
		MethodExecutionContext rollbackExecutionContext = new MethodExecutionContext();
		rollbackExecutionContext.setConnectionInfo(connectionInfo);
		rollbackExecutionContext.setMethod(rollbackMethod);
		rollbackExecutionContext.setTarget(mock(Connection.class));

		listener.afterMethod(rollbackExecutionContext);

		assertThat(connectionContext.getRollbackAt()).isNotNull();
	}

}