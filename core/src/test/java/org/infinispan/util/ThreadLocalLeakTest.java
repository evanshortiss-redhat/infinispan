package org.infinispan.util;

import java.io.File;
import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.infinispan.Cache;
import org.infinispan.commons.test.CommonsTestingUtil;
import org.infinispan.commons.test.TestResourceTracker;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.AbstractInfinispanTest;
import org.infinispan.test.TestingUtil;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Tests whether certain cache set ups result in thread local leaks.
 *
 * @author Galder Zamarreño
 * @since 5.3
 */
@Test(groups = "functional", testName = "util.ThreadLocalLeakTest")
public class ThreadLocalLeakTest extends AbstractInfinispanTest {

   private static final Pattern THREAD_LOCAL_FILTER = Pattern.compile("org\\.infinispan\\..*");

   // Some static thread locals we cannot remove, because
   private static final Set<String> ACCEPTED_THREAD_LOCALS = new HashSet<>(Arrays.asList());

   private String tmpDirectory;

   @BeforeClass
   protected void setUpTempDir() {
      tmpDirectory = CommonsTestingUtil.tmpDirectory(this.getClass());
   }

   @AfterClass
   protected void clearTempDir() {
      org.infinispan.commons.util.Util.recursiveFileRemove(tmpDirectory);
      new File(tmpDirectory).mkdirs();
   }

   public void testCheckThreadLocalLeaks() throws Exception {
      final ConfigurationBuilder builder = new ConfigurationBuilder();
      builder
            .memory().size(4096)
            .locking().concurrencyLevel(2048)
            .persistence().passivation(false)
               .addSingleFileStore().shared(false).preload(true);

      Future<Map<String, Map<ThreadLocal<?>, Object>>> result = fork(() -> {
               TestResourceTracker.testThreadStarted(ThreadLocalLeakTest.this.getTestName());
               Thread forkedThread = doStuffWithCache(builder);

               beforeGC();
               System.gc();
               Thread.sleep(500);
               System.gc();
               afterGC();

               // Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
               List<Thread> threadSet = Arrays.asList(Thread.currentThread(), forkedThread);
               Map<String, Map<ThreadLocal<?>, Object>> allThreadLocals = new HashMap<>();
               for (Thread thread : threadSet) {
                  Map<ThreadLocal<?>, Object> threadLocalLeaks = findThreadLocalLeaks(thread);
                  if (threadLocalLeaks != null && !threadLocalLeaks.isEmpty())
                     allThreadLocals.put(thread.getName(), threadLocalLeaks);
               }

               return allThreadLocals;
            });

      Map<String, Map<ThreadLocal<?>, Object>> allThreadLocals = result.get(30, TimeUnit.SECONDS);
      if (!allThreadLocals.isEmpty())
         throw new IllegalStateException("Thread locals still present: " + allThreadLocals);
   }

   private Thread doStuffWithCache(ConfigurationBuilder builder) {
      GlobalConfigurationBuilder globalBuilder = new GlobalConfigurationBuilder().nonClusteredDefault();
      globalBuilder.globalState().enable().persistentLocation(tmpDirectory);
      EmbeddedCacheManager cm = new DefaultCacheManager(globalBuilder.build());
      Thread forkedThread;
      try {
         cm.defineConfiguration("leak", builder.build());
         final Cache<Object, Object> c = cm.getCache("leak");
         c.put("key1", "value1");

         forkedThread = inNewThread(() -> {
            Cache<Object, Object> c1 = cm.getCache("leak");
            c1.put("key2", "value2");
            c1 = null;
            TestingUtil.sleepThread(2000);
         });

         c.put("key3", "value3");
      } finally {
         TestingUtil.killCacheManagers(cm);
      }
      return forkedThread;
   }

   private void beforeGC() {
      // do nothing
   }

   private void afterGC() {
      // do nothing
   }

   private Map<ThreadLocal<?>, Object> findThreadLocalLeaks(Thread thread) throws Exception {
      // Get a reference to the thread locals table of the current thread
      Field threadLocalsField = Thread.class.getDeclaredField("threadLocals");
      threadLocalsField.setAccessible(true);
      Object threadLocalTable = threadLocalsField.get(thread);

      // Get a reference to the array holding the thread local variables inside the
      // ThreadLocalMap of the current thread
      Class threadLocalMapClass = Class.forName("java.lang.ThreadLocal$ThreadLocalMap");
      Field tableField = threadLocalMapClass.getDeclaredField("table");
      tableField.setAccessible(true);
      Object table = null;
      try {
         table = tableField.get(threadLocalTable);
      } catch (NullPointerException e) {
         // Ignore
         return null;
      }

      Class<?> entryClass = Class.forName("java.lang.ThreadLocal$ThreadLocalMap$Entry");
      Field valueField = entryClass.getDeclaredField("value");
      valueField.setAccessible(true);

      Map<ThreadLocal<?>, Object> threadLocals = new HashMap<ThreadLocal<?>, Object>();
      for (int i=0; i < Array.getLength(table); i++) {
         // Each entry in the table array of ThreadLocalMap is an Entry object
         // representing the thread local reference and its value
         Reference<ThreadLocal<?>> entry = (Reference) Array.get(table, i);
         if (entry != null) {
            // Get a reference to the thread local object
            ThreadLocal<?> threadLocal = entry.get();
            Object value = valueField.get(entry);
            if (threadLocal != null) {
               if (filterThreadLocals(threadLocal, value) && !ACCEPTED_THREAD_LOCALS.contains(threadLocal.getClass().getCanonicalName())) {
                  log.error("Thread local leak: " + threadLocal);
                  threadLocals.put(threadLocal, value);
                  // threadLocal.remove();
               }
            } else {
               log.warn("Thread local is not accessible, but it wasn't removed either: " + value);
            }
         }
      }

      return threadLocals;
   }

   private boolean filterThreadLocals(ThreadLocal<?> tl, Object value) {
      String tlClassName = tl.getClass().getName();
      String valueClassName = value != null ? value.getClass().getName() : "";
      log.tracef("Checking thread-local %s = %s", tlClassName, valueClassName);
      if (!THREAD_LOCAL_FILTER.matcher(tlClassName).find()
            && !THREAD_LOCAL_FILTER.matcher(valueClassName).find()) {
         return false;
      }
      return !ACCEPTED_THREAD_LOCALS.contains(tlClassName) && !ACCEPTED_THREAD_LOCALS.contains(valueClassName);
   }

}
