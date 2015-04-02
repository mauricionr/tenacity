package com.yammer.tenacity.tests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;
import com.yammer.tenacity.core.TenacityObservableCommand;
import com.yammer.tenacity.core.config.BreakerboxConfiguration;
import com.yammer.tenacity.core.config.SemaphoreConfiguration;
import com.yammer.tenacity.core.config.TenacityConfiguration;
import com.yammer.tenacity.core.properties.TenacityPropertyKey;
import com.yammer.tenacity.core.properties.TenacityPropertyRegister;
import com.yammer.tenacity.testing.TenacityTestRule;
import io.dropwizard.util.Duration;
import org.junit.Rule;
import org.junit.Test;
import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TenacityObservableCommandTest {
    @Rule
    public final TenacityTestRule tenacityTestRule = new TenacityTestRule();

    private static void executeTimeoutAndVerify(final TenacityObservableCommand<Boolean> timeoutCommand) {
        try {
            assertTrue(timeoutCommand.observe().toBlocking().single());
        } catch (HystrixRuntimeException err) {
            assertThat(err.getFailureType()).isEqualTo(HystrixRuntimeException.FailureType.TIMEOUT);
        }

        assertThat(timeoutCommand.isResponseTimedOut()).isTrue();
        assertThat(timeoutCommand.getMetrics().getCumulativeCount(HystrixRollingNumberEvent.TIMEOUT)).isEqualTo(1);
    }

    @Test
    public void shouldTimeout() {
        executeTimeoutAndVerify(new TimeoutObservableCommand(Duration.milliseconds(1500)));
    }

    @Test
    public void shouldTimeoutAndRespectsKeyProperties() {
        final TenacityConfiguration tenacityConfiguration = new TenacityConfiguration();
        tenacityConfiguration.setExecutionIsolationThreadTimeoutInMillis(100);

        new TenacityPropertyRegister(
                ImmutableMap.<TenacityPropertyKey, TenacityConfiguration>of(DependencyKey.OBSERVABLE_TIMEOUT, tenacityConfiguration),
                new BreakerboxConfiguration())
                .register();

        executeTimeoutAndVerify(new TimeoutObservableCommand(Duration.milliseconds(300)));
    }

    @Test
    public void shouldNotTimeout() {
        final TenacityObservableCommand<Boolean> command = new TimeoutObservableCommand(Duration.milliseconds(100));
        assertTrue(command.toObservable().toBlocking().single());
    }

    @Test
    public void shouldNotTimeoutAndRespectsProperties() {
        final TenacityConfiguration tenacityConfiguration = new TenacityConfiguration();
        tenacityConfiguration.setExecutionIsolationThreadTimeoutInMillis(2000);

        new TenacityPropertyRegister(
                ImmutableMap.<TenacityPropertyKey, TenacityConfiguration>of(DependencyKey.OBSERVABLE_TIMEOUT, tenacityConfiguration),
                new BreakerboxConfiguration())
                .register();
        final TenacityObservableCommand<Boolean> command = new TimeoutObservableCommand(Duration.milliseconds(1250));
        assertTrue(command.toObservable().toBlocking().single());
    }

    private static class TimeoutObservableCommand extends TenacityObservableCommand<Boolean> {
        private final Duration sleepDuration;

        public TimeoutObservableCommand(Duration sleepDuration) {
            super(DependencyKey.OBSERVABLE_TIMEOUT);
            this.sleepDuration = sleepDuration;
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.create(new Observable.OnSubscribe<Boolean>() {
                @Override
                public void call(Subscriber<? super Boolean> subscriber) {
                    try {
                        Thread.sleep(sleepDuration.toMilliseconds());
                        subscriber.onNext(true);
                        subscriber.onCompleted();
                    } catch (InterruptedException err) {
                        subscriber.onError(err);
                        fail("Interrupted observable timeout");
                    }
                }
            }).subscribeOn(Schedulers.computation());
        }
    }

    @Test
    public void whenUsingObservableCommandsExperienceRejectionsIfSemaphoreLimitBreached() {
        final TenacityConfiguration tenacityConfiguration = new TenacityConfiguration();
        tenacityConfiguration.setExecutionIsolationThreadTimeoutInMillis(3000);

        new TenacityPropertyRegister(
                ImmutableMap.<TenacityPropertyKey, TenacityConfiguration>of(DependencyKey.OBSERVABLE_TIMEOUT, tenacityConfiguration),
                new BreakerboxConfiguration())
                .register();

        final int defaultSemaphoreMaxConcurrentRequests = new SemaphoreConfiguration().getMaxConcurrentRequests();
        final ImmutableList.Builder<Observable<Boolean>> observables = ImmutableList.builder();
        for (int i = 0; i < defaultSemaphoreMaxConcurrentRequests * 2; ++i) {
            final TimeoutObservableCommand command = new TimeoutObservableCommand(Duration.milliseconds(500));
            observables.add(command.observe());
        }

        for (Observable<Boolean> observable : observables.build()) {
            try {
                assertTrue(observable.toBlocking().single());
            } catch (HystrixRuntimeException err) {
                assertThat(err).isInstanceOf(HystrixRuntimeException.class);
                assertThat(err.getFailureType())
                        .isIn(HystrixRuntimeException.FailureType.REJECTED_SEMAPHORE_EXECUTION,
                                HystrixRuntimeException.FailureType.TIMEOUT);
            }
        }

        assertThat(TenacityObservableCommand
                .getCommandMetrics(DependencyKey.OBSERVABLE_TIMEOUT)
                .getCumulativeCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED))
                .isGreaterThan(0);
        assertThat(TenacityObservableCommand
                .getCommandMetrics(DependencyKey.OBSERVABLE_TIMEOUT)
                .getCumulativeCount(HystrixRollingNumberEvent.TIMEOUT))
                .isEqualTo(0);
        assertThat(TenacityObservableCommand
                .getCommandMetrics(DependencyKey.OBSERVABLE_TIMEOUT)
                .getCumulativeCount(HystrixRollingNumberEvent.SUCCESS))
                .isGreaterThan(0);
    }

    @Test
    public void observableCommandCanAdjustSemaphoreMaxConcurrentExecutions() {
        final SemaphoreConfiguration semaphoreConfiguration = new SemaphoreConfiguration();
        semaphoreConfiguration.setMaxConcurrentRequests(50);

        final TenacityConfiguration tenacityConfiguration = new TenacityConfiguration();
        tenacityConfiguration.setSemaphore(semaphoreConfiguration);
        tenacityConfiguration.setExecutionIsolationThreadTimeoutInMillis(3000);

        new TenacityPropertyRegister(
                ImmutableMap.<TenacityPropertyKey, TenacityConfiguration>of(DependencyKey.OBSERVABLE_TIMEOUT, tenacityConfiguration),
                new BreakerboxConfiguration())
                .register();

        final int defaultSemaphoreMaxConcurrentRequests = new SemaphoreConfiguration().getMaxConcurrentRequests();
        final ImmutableList.Builder<Observable<Boolean>> observables = ImmutableList.builder();
        for (int i = 0; i < defaultSemaphoreMaxConcurrentRequests * 2; ++i) {
            final TimeoutObservableCommand command = new TimeoutObservableCommand(Duration.milliseconds(500));
            observables.add(command.observe());
        }

        for (Observable<Boolean> observable : observables.build()) {
            try {
                assertTrue(observable.toBlocking().single());
            } catch (HystrixRuntimeException err) {
                fail("Failed to execute an observable: " + err);
            }
        }

        assertThat(TenacityObservableCommand
                .getCommandMetrics(DependencyKey.OBSERVABLE_TIMEOUT)
                .getCumulativeCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED))
                .isEqualTo(0);
        assertThat(TenacityObservableCommand
                .getCommandMetrics(DependencyKey.OBSERVABLE_TIMEOUT)
                .getCumulativeCount(HystrixRollingNumberEvent.TIMEOUT))
                .isEqualTo(0);
        assertThat(TenacityObservableCommand
                .getCommandMetrics(DependencyKey.OBSERVABLE_TIMEOUT)
                .getCumulativeCount(HystrixRollingNumberEvent.SUCCESS))
                .isEqualTo(observables.build().size());
    }
}