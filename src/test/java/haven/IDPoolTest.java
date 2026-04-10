package haven;

import me.ender.IDPool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IDPoolTest {

    @Test
    void nextReturnsSequentialIds() {
        IDPool pool = new IDPool(0, 100);
        assertEquals(0, pool.next());
        assertEquals(1, pool.next());
        assertEquals(2, pool.next());
    }

    @Test
    void nextReturnsFromMinimum() {
        IDPool pool = new IDPool(10, 20);
        assertEquals(10, pool.next());
        assertEquals(11, pool.next());
    }

    @Test
    void exhaustedPoolThrows() {
        IDPool pool = new IDPool(0, 0);
        assertEquals(0, pool.next()); // max is inclusive
        assertThrows(RuntimeException.class, pool::next);
    }

    @Test
    void singleIdPool() {
        IDPool pool = new IDPool(5, 6);
        assertEquals(5, pool.next());
        assertEquals(6, pool.next());
        assertThrows(RuntimeException.class, pool::next);
    }

    @Test
    void claimSequential() {
        IDPool pool = new IDPool(0, 100);
        pool.claim(0);
        pool.claim(1);
        pool.claim(2);
        assertEquals(3, pool.next());
    }

    @Test
    void claimWithGap() {
        IDPool pool = new IDPool(0, 100);
        pool.claim(3); // skips 0, 1, 2 -> they go to sparse
        // next should return from sparse (0, 1, or 2)
        long id = pool.next();
        assertTrue(id >= 0 && id <= 2, "Expected sparse ID 0-2, got " + id);
    }

    @Test
    void releaseAndReuse() {
        IDPool pool = new IDPool(0, 100);
        pool.next(); // 0
        pool.next(); // 1
        pool.next(); // 2
        pool.release(1); // return 1 to sparse
        // Next should return 1 from sparse
        assertEquals(1, pool.next());
    }

    @Test
    void releaseLastDecrementsNext() {
        IDPool pool = new IDPool(0, 100);
        pool.next(); // 0
        pool.next(); // 1
        pool.release(1); // release the last allocated -> decrements next
        assertEquals(1, pool.next()); // should get 1 again
    }

    @Test
    void saveAndRestore() {
        IDPool original = new IDPool(0, 100);
        original.next(); // 0
        original.next(); // 1
        original.next(); // 2
        original.release(1); // put 1 in sparse

        // Save to message
        MessageBuf buf = new MessageBuf();
        original.save(buf);

        // Restore from message
        MessageBuf reader = new MessageBuf(buf.fin());
        IDPool restored = new IDPool(reader);

        // Restored pool should behave the same
        assertEquals(1, restored.next()); // from sparse
        assertEquals(3, restored.next()); // sequential
    }

    @Test
    void claimAlreadyClaimed() {
        IDPool pool = new IDPool(0, 100);
        pool.next(); // claims 0
        // claiming 0 again should not crash (just prints debug)
        pool.claim(0); // should handle gracefully
    }
}
