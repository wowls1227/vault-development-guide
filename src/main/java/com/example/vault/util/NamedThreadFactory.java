package com.example.vault.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 스레드 이름을 "prefix-번호" 로 붙여주는 ThreadFactory.
 * 로그에서 어느 스레드(worker-1, rotator-1 ...)가 찍은 것인지 구분하기 위해 사용한다.
 */
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger seq = new AtomicInteger(1);

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
        t.setDaemon(false);
        return t;
    }
}
