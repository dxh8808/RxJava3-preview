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

package io.reactivex.observable.internal.operators;

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import io.reactivex.common.*;
import io.reactivex.common.exceptions.TestException;
import io.reactivex.observable.*;
import io.reactivex.observable.observers.TestObserver;
import io.reactivex.observable.subjects.PublishSubject;

public class SingleSubscribeOnTest {

    @Test
    public void normal() {
        List<Throwable> list = TestCommonHelper.trackPluginErrors();
        try {
            TestScheduler scheduler = new TestScheduler();

            TestObserver<Integer> ts = Single.just(1)
            .subscribeOn(scheduler)
            .test();

            scheduler.advanceTimeBy(1, TimeUnit.SECONDS);

            ts.assertResult(1);

            assertTrue(list.toString(), list.isEmpty());
        } finally {
            RxJavaCommonPlugins.reset();
        }
    }

    @Test
    public void dispose() {
        TestHelper.checkDisposed(PublishSubject.create().singleOrError().subscribeOn(new TestScheduler()));
    }

    @Test
    public void error() {
        Single.error(new TestException())
        .subscribeOn(Schedulers.single())
        .test()
        .awaitDone(5, TimeUnit.SECONDS)
        .assertFailure(TestException.class);
    }
}
