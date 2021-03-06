package tn.mnlr.vripper.services;

import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import lombok.Getter;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class CommonExecutor {
    @Getter
    private final ExecutorService generalExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final ExecutorService schedulerExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    @Getter
    private final Scheduler scheduler = Schedulers.from(schedulerExecutor);

    @PreDestroy
    private void destroy() throws InterruptedException {
        generalExecutor.shutdown();
        generalExecutor.awaitTermination(10, TimeUnit.SECONDS);
        schedulerExecutor.shutdown();
        schedulerExecutor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
