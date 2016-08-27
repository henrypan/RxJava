/**
 * Copyright 2016 Netflix, Inc.
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

package io.reactivex.internal.operators.observable;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.*;
import org.mockito.InOrder;

import io.reactivex.*;
import io.reactivex.disposables.*;
import io.reactivex.functions.Consumer;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.*;
import io.reactivex.subjects.PublishSubject;

public class ObservableAmbTest {

    private TestScheduler scheduler;
    private Scheduler.Worker innerScheduler;

    @Before
    public void setUp() {
        scheduler = new TestScheduler();
        innerScheduler = scheduler.createWorker();
    }

    private Observable<String> createObservable(final String[] values,
            final long interval, final Throwable e) {
        return Observable.unsafeCreate(new ObservableSource<String>() {

            @Override
            public void subscribe(final Observer<? super String> NbpSubscriber) {
                CompositeDisposable parentSubscription = new CompositeDisposable();
                
                NbpSubscriber.onSubscribe(parentSubscription);
                
                long delay = interval;
                for (final String value : values) {
                    parentSubscription.add(innerScheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            NbpSubscriber.onNext(value);
                        }
                    }
                    , delay, TimeUnit.MILLISECONDS));
                    delay += interval;
                }
                parentSubscription.add(innerScheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                            if (e == null) {
                                NbpSubscriber.onComplete();
                            } else {
                                NbpSubscriber.onError(e);
                            }
                    }
                }, delay, TimeUnit.MILLISECONDS));
            }
        });
    }

    @Test
    public void testAmb() {
        Observable<String> observable1 = createObservable(new String[] {
                "1", "11", "111", "1111" }, 2000, null);
        Observable<String> observable2 = createObservable(new String[] {
                "2", "22", "222", "2222" }, 1000, null);
        Observable<String> observable3 = createObservable(new String[] {
                "3", "33", "333", "3333" }, 3000, null);

        @SuppressWarnings("unchecked")
        Observable<String> o = Observable.ambArray(observable1,
                observable2, observable3);

        Observer<String> NbpObserver = TestHelper.mockObserver();
        o.subscribe(NbpObserver);

        scheduler.advanceTimeBy(100000, TimeUnit.MILLISECONDS);

        InOrder inOrder = inOrder(NbpObserver);
        inOrder.verify(NbpObserver, times(1)).onNext("2");
        inOrder.verify(NbpObserver, times(1)).onNext("22");
        inOrder.verify(NbpObserver, times(1)).onNext("222");
        inOrder.verify(NbpObserver, times(1)).onNext("2222");
        inOrder.verify(NbpObserver, times(1)).onComplete();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testAmb2() {
        IOException expectedException = new IOException(
                "fake exception");
        Observable<String> observable1 = createObservable(new String[] {},
                2000, new IOException("fake exception"));
        Observable<String> observable2 = createObservable(new String[] {
                "2", "22", "222", "2222" }, 1000, expectedException);
        Observable<String> observable3 = createObservable(new String[] {},
                3000, new IOException("fake exception"));

        @SuppressWarnings("unchecked")
        Observable<String> o = Observable.ambArray(observable1,
                observable2, observable3);

        Observer<String> NbpObserver = TestHelper.mockObserver();
        o.subscribe(NbpObserver);

        scheduler.advanceTimeBy(100000, TimeUnit.MILLISECONDS);

        InOrder inOrder = inOrder(NbpObserver);
        inOrder.verify(NbpObserver, times(1)).onNext("2");
        inOrder.verify(NbpObserver, times(1)).onNext("22");
        inOrder.verify(NbpObserver, times(1)).onNext("222");
        inOrder.verify(NbpObserver, times(1)).onNext("2222");
        inOrder.verify(NbpObserver, times(1)).onError(expectedException);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testAmb3() {
        Observable<String> observable1 = createObservable(new String[] {
                "1" }, 2000, null);
        Observable<String> observable2 = createObservable(new String[] {},
                1000, null);
        Observable<String> observable3 = createObservable(new String[] {
                "3" }, 3000, null);

        @SuppressWarnings("unchecked")
        Observable<String> o = Observable.ambArray(observable1,
                observable2, observable3);

        Observer<String> NbpObserver = TestHelper.mockObserver();
        o.subscribe(NbpObserver);

        scheduler.advanceTimeBy(100000, TimeUnit.MILLISECONDS);
        InOrder inOrder = inOrder(NbpObserver);
        inOrder.verify(NbpObserver, times(1)).onComplete();
        inOrder.verifyNoMoreInteractions();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSubscriptionOnlyHappensOnce() throws InterruptedException {
        final AtomicLong count = new AtomicLong();
        Consumer<Disposable> incrementer = new Consumer<Disposable>() {
            @Override
            public void accept(Disposable s) {
                count.incrementAndGet();
            }
        };
        
        //this aync stream should emit first
        Observable<Integer> o1 = Observable.just(1).doOnSubscribe(incrementer)
                .delay(100, TimeUnit.MILLISECONDS).subscribeOn(Schedulers.computation());
        //this stream emits second
        Observable<Integer> o2 = Observable.just(1).doOnSubscribe(incrementer)
                .delay(100, TimeUnit.MILLISECONDS).subscribeOn(Schedulers.computation());
        TestObserver<Integer> ts = new TestObserver<Integer>();
        Observable.ambArray(o1, o2).subscribe(ts);
        ts.awaitTerminalEvent(5, TimeUnit.SECONDS);
        ts.assertNoErrors();
        assertEquals(2, count.get());
    }
    
    @Test
    public void testSynchronousSources() {
        // under async subscription the second NbpObservable would complete before
        // the first but because this is a synchronous subscription to sources
        // then second NbpObservable does not get subscribed to before first
        // subscription completes hence first NbpObservable emits result through
        // amb
        int result = Observable.just(1).doOnNext(new Consumer<Integer>() {
            @Override
            public void accept(Integer t) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        //
                    }
            }
        }).ambWith(Observable.just(2)).blockingSingle();
        assertEquals(1, result);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testAmbCancelsOthers() {
        PublishSubject<Integer> source1 = PublishSubject.create();
        PublishSubject<Integer> source2 = PublishSubject.create();
        PublishSubject<Integer> source3 = PublishSubject.create();
        
        TestObserver<Integer> ts = new TestObserver<Integer>();
        
        Observable.ambArray(source1, source2, source3).subscribe(ts);
        
        assertTrue("Source 1 doesn't have subscribers!", source1.hasObservers());
        assertTrue("Source 2 doesn't have subscribers!", source2.hasObservers());
        assertTrue("Source 3 doesn't have subscribers!", source3.hasObservers());
        
        source1.onNext(1);

        assertTrue("Source 1 doesn't have subscribers!", source1.hasObservers());
        assertFalse("Source 2 still has subscribers!", source2.hasObservers());
        assertFalse("Source 2 still has subscribers!", source3.hasObservers());
        
    }

    @SuppressWarnings("unchecked")
    @Test
    public void ambArrayEmpty() {
        assertSame(Observable.empty(), Observable.ambArray());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void ambArraySingleElement() {
        assertSame(Observable.never(), Observable.ambArray(Observable.never()));
    }

}
