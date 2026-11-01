package org.srx.hook;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

// 沿用系统队列和拒绝策略，只把请求视图传递给缩略图工作线程。
final class ThumbnailExecutor extends AbstractExecutorService {
  private final ExecutorService delegate;
  private final Function<Runnable, Runnable> wrapper;

  ThumbnailExecutor(ExecutorService delegate, Function<Runnable, Runnable> wrapper) {
    this.delegate = delegate;
    this.wrapper = wrapper;
  }

  @Override
  public void execute(Runnable command) {
    delegate.execute(wrapper.apply(command));
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }
}
