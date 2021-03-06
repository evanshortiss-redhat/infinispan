package org.infinispan.scattered;

import static org.infinispan.distribution.DistributionTestHelper.addressOf;
import static org.infinispan.distribution.DistributionTestHelper.assertIsInContainerImmortal;
import static org.infinispan.distribution.DistributionTestHelper.isOwner;
import static org.infinispan.distribution.DistributionTestHelper.safeType;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.util.List;

import org.infinispan.Cache;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.ImmortalCacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.persistence.dummy.DummyInMemoryStore;
import org.infinispan.persistence.spi.MarshallableEntry;
import org.infinispan.test.TestingUtil;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class Utils {
   public static void assertOwnershipAndNonOwnership(List<? extends Cache> caches, Object key) {
      EntryVersion ownerVersion = null;
      for (Cache c: caches) {
         DataContainer dc = c.getAdvancedCache().getDataContainer();
         InternalCacheEntry ice = dc.peek(key);
         if (isOwner(c, key)) {
            assert ice != null : "Fail on owner cache " + addressOf(c) + ": dc.get(" + key + ") returned null!";
            assert ice instanceof ImmortalCacheEntry : "Fail on owner cache " + addressOf(c) + ": dc.get(" + key + ") returned " + safeType(ice);
            ownerVersion = ice.getMetadata().version();
         }
      }
      assertNotNull(ownerVersion);
      if (caches.size() == 1) {
         return;
      }
      int equalVersions = 0;
      for (Cache c: caches) {
         DataContainer dc = c.getAdvancedCache().getDataContainer();
         InternalCacheEntry ice = dc.peek(key);
         if (!isOwner(c, key)) {
            if (ice == null) continue;
            if (ice != null && ice.getMetadata() != null && ownerVersion.equals(ice.getMetadata().version())) ++equalVersions;
         }
      }
      assertEquals(equalVersions, 1);
   }

   public static void assertInStores(List<? extends Cache> caches, String key, String value) {
      EntryVersion ownerVersion = null;
      for (Cache c: caches) {
         DummyInMemoryStore store = TestingUtil.<DummyInMemoryStore, Object, Object>getFirstStore(c);
         if (isOwner(c, key)) {
            assertIsInContainerImmortal(c, key);
            MarshallableEntry me = store.loadEntry(key);
            assertEquals(me.getValue(), value);
            ownerVersion = me.getMetadata().version();
         }
      }
      assertNotNull(ownerVersion);
      if (caches.size() == 1) {
         return;
      }
      int equalVersions = 0;
      for (Cache c: caches) {
         DummyInMemoryStore store = TestingUtil.<DummyInMemoryStore, Object, Object>getFirstStore(c);
         if (!isOwner(c, key)) {
            MarshallableEntry me = store.loadEntry(key);
            if (me != null && me.getMetadata() != null && ownerVersion.equals(me.getMetadata().version())) {
               assertEquals(me.getValue(), value);
               ++equalVersions;
            }
         }
      }
      assertEquals(equalVersions, 1);
   }
}
