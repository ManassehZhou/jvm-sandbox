package com.alibaba.jvm.sandbox.core.manager.impl;

import com.alibaba.jvm.sandbox.api.filter.Filter;
import com.alibaba.jvm.sandbox.core.manager.CoreLoadedClassDataSource;
import com.alibaba.jvm.sandbox.core.util.SandboxClassUtils;
import com.alibaba.jvm.sandbox.core.util.SandboxProtector;
import com.alibaba.jvm.sandbox.core.util.matcher.ExtFilterMatcher;
import com.alibaba.jvm.sandbox.core.util.matcher.Matcher;
import com.alibaba.jvm.sandbox.core.util.matcher.UnsupportedMatcher;
import com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructureFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.*;

import static com.alibaba.jvm.sandbox.api.filter.ExtFilter.ExtFilterFactory.make;
import static com.alibaba.jvm.sandbox.core.util.SandboxClassUtils.isComeFromSandboxFamily;
import static com.alibaba.jvm.sandbox.core.util.SandboxStringUtils.toInternalClassName;

/**
 * 已加载类数据源默认实现
 *
 * @author luanjia@taobao.com
 */
public class DefaultCoreLoadedClassDataSource implements CoreLoadedClassDataSource {

    private static final int WAIT_MATCHES_MINUTES = 2;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Instrumentation inst;
    private final boolean isEnableUnsafe;

    private final ForkJoinPool classMatchesForkJoinPool;

    public DefaultCoreLoadedClassDataSource(final Instrumentation inst,
                                            final boolean isEnableUnsafe) {
        this.inst = inst;
        this.isEnableUnsafe = isEnableUnsafe;
        this.classMatchesForkJoinPool = new ForkJoinPool();

    }

    private class ClassMatchesTask extends RecursiveTask<List<Class<?>>> {

        private static final int THREAD_HOLD = 100;

        private final Class<?>[] loadedClasses;

        private final Matcher matcher;

        private final boolean isRemoveUnsupported;

        public ClassMatchesTask(Class<?>[] loadedClasses, Matcher matcher, boolean isRemoveUnsupported) {
            this.loadedClasses = loadedClasses;
            this.matcher = matcher;
            this.isRemoveUnsupported = isRemoveUnsupported;
        }

        @Override
        protected List<Class<?>> compute() {

            if (loadedClasses.length == 0) {

                return new ArrayList<>();

            } else if (loadedClasses.length <= THREAD_HOLD) {

                return findClasses();

            } else {

                int middle = loadedClasses.length / 2;
                List<Class<?>> leftClasses = forkCompute(loadedClasses, 0, middle);
                List<Class<?>> rightClasses = forkCompute(loadedClasses, middle + 1, loadedClasses.length - 1);
                leftClasses.addAll(rightClasses);
                return leftClasses;

            }

        }

        private List<Class<?>> findClasses() {

            List<Class<?>> classes = new ArrayList<>();

            for (Class<?> clazz : loadedClasses) {
                String className = clazz.getName();
                ClassLoader classLoader = clazz.getClassLoader();

                // #242 的建议，过滤掉sandbox家族的类
                if (SandboxClassUtils.startWithCom(className) && isComeFromSandboxFamily(toInternalClassName(className), classLoader)) {
                    continue;
                }

                // 过滤掉对于JVM认为不可修改的类
                if (isRemoveUnsupported && !inst.isModifiableClass(clazz)) {
                    // logger.debug("remove from findForReTransform, because class:{} is unModifiable", clazz.getName());
                    continue;
                }
                try {
                    if (isRemoveUnsupported) {
                        if (new UnsupportedMatcher(classLoader, isEnableUnsafe)
                                .and(matcher)
                                .matching(ClassStructureFactory.createClassStructure(clazz))
                                .isMatched()) {
                            classes.add(clazz);
                        }
                    } else {
                        if (matcher.matching(ClassStructureFactory.createClassStructure(clazz)).isMatched()) {
                            classes.add(clazz);
                        }
                    }


                } catch (Throwable cause) {
                    // 在这里可能会遇到非常坑爹的模块卸载错误
                    // 当一个URLClassLoader被动态关闭之后，但JVM已经加载的类并不知情（因为没有GC）
                    // 所以当尝试获取这个类更多详细信息的时候会引起关联类的ClassNotFoundException等未知的错误（取决于底层ClassLoader的实现）
                    // 这里没有办法穷举出所有的异常情况，所以catch Throwable来完成异常容灾处理
                    // 当解析类出现异常的时候，直接简单粗暴的认为根本没有这个类就好了
                    logger.debug("remove from findForReTransform, because loading class:{} occur an exception", clazz.getName(), cause);
                }

            }

            return classes;
        }

        private List<Class<?>> forkCompute(Class<?>[] loadedClasses, int from, int to) {
            Class<?>[] leftClasses = Arrays.copyOfRange(loadedClasses, from, to);
            ClassMatchesTask task = new ClassMatchesTask(leftClasses, matcher, isRemoveUnsupported);
            task.fork();
            return task.join();
        }

    }

    @Override
    public Set<Class<?>> list() {
        final Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            classes.add(clazz);
        }
        return classes;
    }

    @Override
    public Iterator<Class<?>> iteratorForLoadedClasses() {
        return new Iterator<Class<?>>() {

            final Class<?>[] loaded = inst.getAllLoadedClasses();
            int pos = 0;

            @Override
            public boolean hasNext() {
                return pos < loaded.length;
            }

            @Override
            public Class<?> next() {
                return loaded[pos++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

        };
    }

    @Override
    public List<Class<?>> findForReTransform(final Matcher matcher) {
        return find(matcher, true);
    }

    private List<Class<?>> find(final Matcher matcher, final boolean isRemoveUnsupported) {

        SandboxProtector.getOrCreateInstance().enterProtecting();

        try {

            // 大量计算部分，需要非常小心处理性能问题，能不计算就不计算
            final Iterator<Class<?>> itForLoaded = iteratorForLoadedClasses();

            // fork - join

            Class[] allLoadedClasses = inst.getAllLoadedClasses();

            ClassMatchesTask task = new ClassMatchesTask(allLoadedClasses, matcher, isRemoveUnsupported);

            classMatchesForkJoinPool.submit(task);

            try {

                return task.get(WAIT_MATCHES_MINUTES, TimeUnit.MINUTES);

            } catch (InterruptedException | ExecutionException | TimeoutException cause) {

                logger.info("Failed when compute matches.", cause);

                return Collections.emptyList();

            }

        } finally {

            SandboxProtector.getOrCreateInstance().exitProtecting();

        }

    }


    /**
     * 根据过滤器搜索出匹配的类集合
     *
     * @param filter 扩展过滤器
     * @return 匹配的类集合
     */
    @Override
    public Set<Class<?>> find(Filter filter) {
        return new LinkedHashSet<Class<?>>(find(new ExtFilterMatcher(make(filter)), false));
    }

}
