/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.RowPagesBuilder;
import io.prestosql.Session;
import io.prestosql.connector.CatalogName;
import io.prestosql.memory.context.MemoryTrackingContext;
import io.prestosql.metadata.OutputTableHandle;
import io.prestosql.metadata.Signature;
import io.prestosql.operator.AggregationOperator.AggregationOperatorFactory;
import io.prestosql.operator.DevNullOperator.DevNullOperatorFactory;
import io.prestosql.operator.TableWriterOperator.TableWriterInfo;
import io.prestosql.operator.TableWriterOperator.TableWriterOperatorFactory;
import io.prestosql.operator.aggregation.InternalAggregationFunction;
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.ConnectorInsertTableHandle;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;
import io.prestosql.spi.connector.ConnectorPageSink;
import io.prestosql.spi.connector.ConnectorPageSinkProvider;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.type.Type;
import io.prestosql.split.PageSinkManager;
import io.prestosql.sql.planner.plan.AggregationNode;
import io.prestosql.sql.planner.plan.PlanNodeId;
import io.prestosql.sql.planner.plan.TableWriterNode.CreateTarget;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.FunctionKind.AGGREGATE;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.operator.PageAssertions.assertPageEquals;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static io.prestosql.testing.TestingTaskContext.createTaskContext;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestTableWriterOperator
{
    private static final CatalogName CONNECTOR_ID = new CatalogName("testConnectorId");
    private static final InternalAggregationFunction LONG_MAX = createTestMetadataManager().getAggregateFunctionImplementation(
            new Signature("max", AGGREGATE, BIGINT.getTypeSignature(), BIGINT.getTypeSignature()));
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    @BeforeClass
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test-executor-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testBlockedPageSink()
    {
        BlockingPageSink blockingPageSink = new BlockingPageSink();
        Operator operator = createTableWriterOperator(blockingPageSink);

        // initial state validation
        assertTrue(operator.isBlocked().isDone());
        assertFalse(operator.isFinished());
        assertTrue(operator.needsInput());

        // blockingPageSink that will return blocked future
        operator.addInput(rowPagesBuilder(BIGINT).row(42).build().get(0));

        assertFalse(operator.isBlocked().isDone());
        assertFalse(operator.isFinished());
        assertFalse(operator.needsInput());
        assertNull(operator.getOutput());

        // complete previously blocked future
        blockingPageSink.complete();

        assertTrue(operator.isBlocked().isDone());
        assertFalse(operator.isFinished());
        assertTrue(operator.needsInput());

        // add second page
        operator.addInput(rowPagesBuilder(BIGINT).row(44).build().get(0));

        assertFalse(operator.isBlocked().isDone());
        assertFalse(operator.isFinished());
        assertFalse(operator.needsInput());

        // finish operator, state hasn't changed
        operator.finish();

        assertFalse(operator.isBlocked().isDone());
        assertFalse(operator.isFinished());
        assertFalse(operator.needsInput());

        // complete previously blocked future
        blockingPageSink.complete();
        // and getOutput which actually finishes the operator
        List<Type> expectedTypes = ImmutableList.of(BIGINT, VARBINARY);
        assertPageEquals(expectedTypes,
                operator.getOutput(),
                rowPagesBuilder(expectedTypes).row(2, null).build().get(0));

        assertTrue(operator.isBlocked().isDone());
        assertTrue(operator.isFinished());
        assertFalse(operator.needsInput());
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void addInputFailsOnBlockedOperator()
    {
        Operator operator = createTableWriterOperator(new BlockingPageSink());

        operator.addInput(rowPagesBuilder(BIGINT).row(42).build().get(0));

        assertFalse(operator.isBlocked().isDone());
        assertFalse(operator.needsInput());

        operator.addInput(rowPagesBuilder(BIGINT).row(42).build().get(0));
    }

    @Test
    public void testTableWriterInfo()
    {
        PageSinkManager pageSinkManager = new PageSinkManager();
        pageSinkManager.addConnectorPageSinkProvider(CONNECTOR_ID, new ConstantPageSinkProvider(new TableWriteInfoTestPageSink()));
        TableWriterOperator tableWriterOperator = (TableWriterOperator) createTableWriterOperator(
                pageSinkManager,
                new DevNullOperatorFactory(1, new PlanNodeId("test")),
                ImmutableList.of(BIGINT, VARBINARY));

        RowPagesBuilder rowPagesBuilder = rowPagesBuilder(BIGINT);
        for (int i = 0; i < 100; i++) {
            rowPagesBuilder.addSequencePage(100, 0);
        }
        List<Page> pages = rowPagesBuilder.build();

        long peakMemoryUsage = 0;
        long validationCpuNanos = 0;
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            peakMemoryUsage += page.getRetainedSizeInBytes();
            validationCpuNanos += page.getPositionCount();
            tableWriterOperator.addInput(page);
            TableWriterInfo info = tableWriterOperator.getInfo();
            assertEquals(info.getPageSinkPeakMemoryUsage(), peakMemoryUsage);
            assertEquals((long) (info.getValidationCpuTime().getValue(NANOSECONDS)), validationCpuNanos);
        }
    }

    @Test
    public void testStatisticsAggregation()
            throws Exception
    {
        PageSinkManager pageSinkManager = new PageSinkManager();
        pageSinkManager.addConnectorPageSinkProvider(CONNECTOR_ID, new ConstantPageSinkProvider(new TableWriteInfoTestPageSink()));
        ImmutableList<Type> outputTypes = ImmutableList.of(BIGINT, VARBINARY, BIGINT);
        Session session = testSessionBuilder()
                .setSystemProperty("statistics_cpu_timer_enabled", "true")
                .build();
        DriverContext driverContext = createTaskContext(executor, scheduledExecutor, session)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
        TableWriterOperator operator = (TableWriterOperator) createTableWriterOperator(
                pageSinkManager,
                new AggregationOperatorFactory(
                        1,
                        new PlanNodeId("test"),
                        AggregationNode.Step.SINGLE,
                        ImmutableList.of(LONG_MAX.bind(ImmutableList.of(0), Optional.empty())),
                        true),
                outputTypes,
                session,
                driverContext);

        operator.addInput(rowPagesBuilder(BIGINT).row(42).build().get(0));
        operator.addInput(rowPagesBuilder(BIGINT).row(43).build().get(0));

        assertTrue(operator.isBlocked().isDone());
        assertTrue(operator.needsInput());

        assertThat(driverContext.getSystemMemoryUsage()).isGreaterThan(0);
        assertEquals(driverContext.getMemoryUsage(), 0);

        operator.finish();
        assertFalse(operator.isFinished());

        assertPageEquals(outputTypes, operator.getOutput(),
                rowPagesBuilder(outputTypes)
                        .row(null, null, 43).build().get(0));

        assertPageEquals(outputTypes, operator.getOutput(),
                rowPagesBuilder(outputTypes)
                        .row(2, null, null).build().get(0));

        assertTrue(operator.isBlocked().isDone());
        assertFalse(operator.needsInput());
        assertTrue(operator.isFinished());

        operator.close();
        assertMemoryIsReleased(operator);

        TableWriterInfo info = operator.getInfo();
        assertThat(info.getStatisticsWallTime().getValue(NANOSECONDS)).isGreaterThan(0);
        assertThat(info.getStatisticsCpuTime().getValue(NANOSECONDS)).isGreaterThan(0);
    }

    private void assertMemoryIsReleased(TableWriterOperator tableWriterOperator)
    {
        OperatorContext tableWriterOperatorOperatorContext = tableWriterOperator.getOperatorContext();
        MemoryTrackingContext tableWriterMemoryContext = tableWriterOperatorOperatorContext.getOperatorMemoryContext();
        assertEquals(tableWriterMemoryContext.getSystemMemory(), 0);
        assertEquals(tableWriterMemoryContext.getUserMemory(), 0);
        assertEquals(tableWriterMemoryContext.getRevocableMemory(), 0);

        Operator statisticAggregationOperator = tableWriterOperator.getStatisticAggregationOperator();
        assertTrue(statisticAggregationOperator instanceof AggregationOperator);
        AggregationOperator aggregationOperator = (AggregationOperator) statisticAggregationOperator;
        OperatorContext aggregationOperatorOperatorContext = aggregationOperator.getOperatorContext();
        MemoryTrackingContext aggregationOperatorMemoryContext = aggregationOperatorOperatorContext.getOperatorMemoryContext();
        assertEquals(aggregationOperatorMemoryContext.getSystemMemory(), 0);
        assertEquals(aggregationOperatorMemoryContext.getUserMemory(), 0);
        assertEquals(aggregationOperatorMemoryContext.getRevocableMemory(), 0);
    }

    private Operator createTableWriterOperator(BlockingPageSink blockingPageSink)
    {
        PageSinkManager pageSinkManager = new PageSinkManager();
        pageSinkManager.addConnectorPageSinkProvider(CONNECTOR_ID, new ConstantPageSinkProvider(blockingPageSink));
        return createTableWriterOperator(pageSinkManager, new DevNullOperatorFactory(1, new PlanNodeId("test")), ImmutableList.of(BIGINT, VARBINARY));
    }

    private Operator createTableWriterOperator(PageSinkManager pageSinkManager, OperatorFactory statisticsAggregation, List<Type> outputTypes)
    {
        return createTableWriterOperator(pageSinkManager, statisticsAggregation, outputTypes, TEST_SESSION);
    }

    private Operator createTableWriterOperator(PageSinkManager pageSinkManager, OperatorFactory statisticsAggregation, List<Type> outputTypes, Session session)
    {
        DriverContext driverContext = createTaskContext(executor, scheduledExecutor, session)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
        return createTableWriterOperator(pageSinkManager, statisticsAggregation, outputTypes, session, driverContext);
    }

    private Operator createTableWriterOperator(
            PageSinkManager pageSinkManager,
            OperatorFactory statisticsAggregation,
            List<Type> outputTypes,
            Session session,
            DriverContext driverContext)
    {
        TableWriterOperatorFactory factory = new TableWriterOperatorFactory(
                0,
                new PlanNodeId("test"),
                pageSinkManager,
                new CreateTarget(new OutputTableHandle(
                        CONNECTOR_ID,
                        new ConnectorTransactionHandle() {},
                        new ConnectorOutputTableHandle() {}),
                        new SchemaTableName("testSchema", "testTable")),
                ImmutableList.of(0),
                session,
                statisticsAggregation,
                outputTypes);
        return factory.createOperator(driverContext);
    }

    private static class ConstantPageSinkProvider
            implements ConnectorPageSinkProvider
    {
        private final ConnectorPageSink pageSink;

        private ConstantPageSinkProvider(ConnectorPageSink pageSink)
        {
            this.pageSink = pageSink;
        }

        @Override
        public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorOutputTableHandle outputTableHandle)
        {
            return pageSink;
        }

        @Override
        public ConnectorPageSink createPageSink(ConnectorTransactionHandle transactionHandle, ConnectorSession session, ConnectorInsertTableHandle insertTableHandle)
        {
            return pageSink;
        }
    }

    private static class BlockingPageSink
            implements ConnectorPageSink
    {
        private CompletableFuture<?> future = new CompletableFuture<>();
        private CompletableFuture<Collection<Slice>> finishFuture = new CompletableFuture<>();

        @Override
        public CompletableFuture<?> appendPage(Page page)
        {
            future = new CompletableFuture<>();
            return future;
        }

        @Override
        public CompletableFuture<Collection<Slice>> finish()
        {
            finishFuture = new CompletableFuture<>();
            return finishFuture;
        }

        @Override
        public void abort()
        {
        }

        void complete()
        {
            future.complete(null);
            finishFuture.complete(ImmutableList.of());
        }
    }

    private static class TableWriteInfoTestPageSink
            implements ConnectorPageSink
    {
        private final List<Page> pages = new ArrayList<>();

        @Override
        public CompletableFuture<?> appendPage(Page page)
        {
            pages.add(page);
            return NOT_BLOCKED;
        }

        @Override
        public CompletableFuture<Collection<Slice>> finish()
        {
            return completedFuture(ImmutableList.of());
        }

        @Override
        public long getSystemMemoryUsage()
        {
            long memoryUsage = 0;
            for (Page page : pages) {
                memoryUsage += page.getRetainedSizeInBytes();
            }
            return memoryUsage;
        }

        @Override
        public long getValidationCpuNanos()
        {
            long validationCpuNanos = 0;
            for (Page page : pages) {
                validationCpuNanos += page.getPositionCount();
            }
            return validationCpuNanos;
        }

        @Override
        public void abort() {}
    }
}
