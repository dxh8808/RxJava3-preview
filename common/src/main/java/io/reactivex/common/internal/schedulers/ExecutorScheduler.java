/**
 * Copyright (c) 2016-present, RxJava Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.common.internal.schedulers;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import io.reactivex.common.*;
import io.reactivex.common.annotations.NonNull;
import io.reactivex.common.disposables.CompositeDisposable;
import io.reactivex.common.internal.disposables.*;
import io.reactivex.common.internal.queues.AbstractMpscLinkedQueue;
import io.reactivex.common.internal.schedulers.ExecutorScheduler.ExecutorWorker.BooleanRunnable;

/**
 * Wraps an Executor and provides the Scheduler API over it.
 */
public final class ExecutorScheduler extends Scheduler {

    @NonNull
    final Executor executor;

    static final Scheduler HELPER = Schedulers.single();

    public ExecutorScheduler(@NonNull Executor executor) {
        this.executor = executor;
    }

    @NonNull
    @Override
    public Worker createWorker() {
        return new ExecutorWorker(executor);
    }

    @NonNull
    @Override
    public Disposable scheduleDirect(@NonNull Runnable run) {
        Runnable decoratedRun = RxJavaCommonPlugins.onSchedule(run);
        try {
            if (executor instanceof ExecutorService) {
                ScheduledDirectTask task = new ScheduledDirectTask(decoratedRun);
                Future<?> f = ((ExecutorService)executor).submit(task);
                task.setFuture(f);
                return task;
            }

            BooleanRunnable br = new BooleanRunnable(decoratedRun);
            executor.execute(br);
            return br;
        } catch (RejectedExecutionException ex) {
            RxJavaCommonPlugins.onError(ex);
            return REJECTED;
        }
    }

    @NonNull
    @Override
    public Disposable scheduleDirect(@NonNull Runnable run, final long delay, final TimeUnit unit) {
        final Runnable decoratedRun = RxJavaCommonPlugins.onSchedule(run);
        if (executor instanceof ScheduledExecutorService) {
            try {
                ScheduledDirectTask task = new ScheduledDirectTask(decoratedRun);
                Future<?> f = ((ScheduledExecutorService)executor).schedule(task, delay, unit);
                task.setFuture(f);
                return task;
            } catch (RejectedExecutionException ex) {
                RxJavaCommonPlugins.onError(ex);
                return REJECTED;
            }
        }

        final DelayedRunnable dr = new DelayedRunnable(decoratedRun);

        Disposable delayed = HELPER.scheduleDirect(new DelayedDispose(dr), delay, unit);

        dr.timed.replace(delayed);

        return dr;
    }

    @NonNull
    @Override
    public Disposable schedulePeriodicallyDirect(@NonNull Runnable run, long initialDelay, long period, TimeUnit unit) {
        if (executor instanceof ScheduledExecutorService) {
            Runnable decoratedRun = RxJavaCommonPlugins.onSchedule(run);
            try {
                ScheduledDirectPeriodicTask task = new ScheduledDirectPeriodicTask(decoratedRun);
                Future<?> f = ((ScheduledExecutorService)executor).scheduleAtFixedRate(task, initialDelay, period, unit);
                task.setFuture(f);
                return task;
            } catch (RejectedExecutionException ex) {
                RxJavaCommonPlugins.onError(ex);
                return REJECTED;
            }
        }
        return super.schedulePeriodicallyDirect(run, initialDelay, period, unit);
    }
    /* public: test support. */
    public static final class ExecutorWorker extends Scheduler.Worker implements Runnable {
        final Executor executor;

        final AbstractMpscLinkedQueue<Runnable> queue;

        volatile boolean disposed;

        final AtomicInteger wip = new AtomicInteger();

        final CompositeDisposable tasks = new CompositeDisposable();

        public ExecutorWorker(Executor executor) {
            this.executor = executor;
            this.queue = new AbstractMpscLinkedQueue<Runnable>() { };
        }

        @NonNull
        @Override
        public Disposable schedule(@NonNull Runnable run) {
            if (disposed) {
                return REJECTED;
            }

            Runnable decoratedRun = RxJavaCommonPlugins.onSchedule(run);
            BooleanRunnable br = new BooleanRunnable(decoratedRun);

            queue.offer(br);

            if (wip.getAndIncrement() == 0) {
                try {
                    executor.execute(this);
                } catch (RejectedExecutionException ex) {
                    disposed = true;
                    queue.clear();
                    RxJavaCommonPlugins.onError(ex);
                    return REJECTED;
                }
            }

            return br;
        }

        @NonNull
        @Override
        public Disposable schedule(@NonNull Runnable run, long delay, @NonNull TimeUnit unit) {
            if (delay <= 0) {
                return schedule(run);
            }
            if (disposed) {
                return REJECTED;
            }


            SequentialDisposable first = new SequentialDisposable();

            final SequentialDisposable mar = new SequentialDisposable(first);

            final Runnable decoratedRun = RxJavaCommonPlugins.onSchedule(run);

            ScheduledRunnable sr = new ScheduledRunnable(new SequentialDispose(mar, decoratedRun), tasks);
            tasks.add(sr);

            if (executor instanceof ScheduledExecutorService) {
                try {
                    Future<?> f = ((ScheduledExecutorService)executor).schedule((Callable<Object>)sr, delay, unit);
                    sr.setFuture(f);
                } catch (RejectedExecutionException ex) {
                    disposed = true;
                    RxJavaCommonPlugins.onError(ex);
                    return REJECTED;
                }
            } else {
                final Disposable d = HELPER.scheduleDirect(sr, delay, unit);
                sr.setFuture(new DisposeOnCancel(d));
            }

            first.replace(sr);

            return mar;
        }

        @Override
        public void dispose() {
            if (!disposed) {
                disposed = true;
                tasks.dispose();
                if (wip.getAndIncrement() == 0) {
                    queue.clear();
                }
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }

        @Override
        public void run() {
            int missed = 1;
            final AbstractMpscLinkedQueue<Runnable> q = queue;
            for (;;) {

                if (disposed) {
                    q.clear();
                    return;
                }

                for (;;) {
                    Runnable run = q.poll();
                    if (run == null) {
                        break;
                    }
                    run.run();

                    if (disposed) {
                        q.clear();
                        return;
                    }
                }

                if (disposed) {
                    q.clear();
                    return;
                }

                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
        }

        static final class BooleanRunnable extends AtomicBoolean implements Runnable, Disposable {

            private static final long serialVersionUID = -2421395018820541164L;

            final Runnable actual;
            BooleanRunnable(Runnable actual) {
                this.actual = actual;
            }

            @Override
            public void run() {
                if (get()) {
                    return;
                }
                try {
                    actual.run();
                } finally {
                    lazySet(true);
                }
            }

            @Override
            public void dispose() {
                lazySet(true);
            }

            @Override
            public boolean isDisposed() {
                return get();
            }
        }

        final class SequentialDispose implements Runnable {
            private final SequentialDisposable mar;
            private final Runnable decoratedRun;

            SequentialDispose(SequentialDisposable mar, Runnable decoratedRun) {
                this.mar = mar;
                this.decoratedRun = decoratedRun;
            }

            @Override
            public void run() {
                mar.replace(schedule(decoratedRun));
            }
        }
    }

    static final class DelayedRunnable extends AtomicReference<Runnable> implements Runnable, Disposable {

        private static final long serialVersionUID = -4101336210206799084L;

        final SequentialDisposable timed;

        final SequentialDisposable direct;

        DelayedRunnable(Runnable run) {
            super(run);
            this.timed = new SequentialDisposable();
            this.direct = new SequentialDisposable();
        }

        @Override
        public void run() {
            Runnable r = get();
            if (r != null) {
                try {
                    r.run();
                } finally {
                    lazySet(null);
                    timed.lazySet(DisposableHelper.DISPOSED);
                    direct.lazySet(DisposableHelper.DISPOSED);
                }
            }
        }

        @Override
        public boolean isDisposed() {
            return get() == null;
        }

        @Override
        public void dispose() {
            if (getAndSet(null) != null) {
                timed.dispose();
                direct.dispose();
            }
        }
    }

    final class DelayedDispose implements Runnable {
        private final DelayedRunnable dr;

        DelayedDispose(DelayedRunnable dr) {
            this.dr = dr;
        }

        @Override
        public void run() {
            dr.direct.replace(scheduleDirect(dr));
        }
    }
}
