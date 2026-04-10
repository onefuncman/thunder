package haven;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

public class CachedFunctionTest {

    @Test
    void applyCachesResult() {
        int[] callCount = {0};
        CachedFunction<String, Integer> cf = new CachedFunction<>(10, s -> {
            callCount[0]++;
            return s.length();
        });
        assertEquals(5, cf.apply("hello"));
        assertEquals(5, cf.apply("hello"));
        assertEquals(1, callCount[0]); // only called once
    }

    @Test
    void applyDifferentKeys() {
        int[] callCount = {0};
        CachedFunction<String, Integer> cf = new CachedFunction<>(10, s -> {
            callCount[0]++;
            return s.length();
        });
        assertEquals(5, cf.apply("hello"));
        assertEquals(3, cf.apply("bye"));
        assertEquals(2, callCount[0]);
    }

    @Test
    void evictsWhenFull() {
        int[] callCount = {0};
        CachedFunction<Integer, Integer> cf = new CachedFunction<>(3, i -> {
            callCount[0]++;
            return i * 10;
        });
        cf.apply(1);
        cf.apply(2);
        cf.apply(3);
        assertEquals(3, callCount[0]);
        // Access 1 to make it recently used
        cf.apply(1);
        assertEquals(3, callCount[0]); // still cached
        // Add 4 — should evict 2 (least recently used)
        cf.apply(4);
        assertEquals(4, callCount[0]);
        // Access 2 again — should need recomputation
        cf.apply(2);
        assertEquals(5, callCount[0]);
    }

    @Test
    void disposeCalledOnEviction() {
        List<Integer> disposed = new ArrayList<>();
        CachedFunction<Integer, Integer> cf = new CachedFunction<>(2, i -> i * 10, disposed::add);
        cf.apply(1); // cache: {1->10}
        cf.apply(2); // cache: {1->10, 2->20}
        cf.apply(3); // evicts 1 (LRU), cache: {2->20, 3->30}
        assertEquals(1, disposed.size());
        assertEquals(10, disposed.get(0));
    }

    @Test
    void noDisposeWithoutEviction() {
        List<String> disposed = new ArrayList<>();
        CachedFunction<String, String> cf = new CachedFunction<>(10, s -> s.toUpperCase(), disposed::add);
        cf.apply("hello");
        cf.apply("world");
        assertTrue(disposed.isEmpty());
    }

    @Test
    void cacheSizeOne() {
        int[] callCount = {0};
        CachedFunction<Integer, Integer> cf = new CachedFunction<>(1, i -> {
            callCount[0]++;
            return i;
        });
        cf.apply(1);
        cf.apply(1); // cached
        assertEquals(1, callCount[0]);
        cf.apply(2); // evicts 1
        cf.apply(1); // recomputed
        assertEquals(3, callCount[0]);
    }

    @Test
    void implementsFunction() {
        CachedFunction<String, Integer> cf = new CachedFunction<>(10, String::length);
        Function<String, Integer> f = cf;
        assertEquals(3, f.apply("abc"));
    }

    @Test
    void nullDispose() {
        // Null dispose consumer should not throw
        CachedFunction<Integer, Integer> cf = new CachedFunction<>(1, i -> i * 2, null);
        cf.apply(1);
        cf.apply(2); // evicts without error
        assertEquals(4, cf.apply(2));
    }
}
