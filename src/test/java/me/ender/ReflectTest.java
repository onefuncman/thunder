package me.ender;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ReflectTest {

    // Test helper classes
    static class Parent {
        private String parentField = "parent";
        private int parentInt = 42;
    }

    static class Child extends Parent {
        private String childField = "child";
        private double childDouble = 3.14;
        private boolean childBool = true;
    }

    // --- getFieldValue ---

    @Test
    void getFieldValue() {
        Child c = new Child();
        assertEquals("child", Reflect.getFieldValue(c, "childField"));
    }

    @Test
    void getFieldValueInherited() {
        Child c = new Child();
        assertEquals("parent", Reflect.getFieldValue(c, "parentField"));
    }

    @Test
    void getFieldValueMissing() {
        Child c = new Child();
        assertNull(Reflect.getFieldValue(c, "nonexistent"));
    }

    @Test
    void getFieldValueTyped() {
        Child c = new Child();
        String val = Reflect.getFieldValue(c, "childField", String.class);
        assertEquals("child", val);
    }

    @Test
    void getFieldValueTypedWrongClass() {
        Child c = new Child();
        Integer val = Reflect.getFieldValue(c, "childField", Integer.class);
        assertNull(val);
    }

    @Test
    void getFieldValueInt() {
        Child c = new Child();
        assertEquals(42, Reflect.getFieldValueInt(c, "parentInt"));
    }

    @Test
    void getFieldValueIntMissing() {
        Child c = new Child();
        assertEquals(0, Reflect.getFieldValueInt(c, "nonexistent"));
    }

    @Test
    void getFieldValueDouble() {
        Child c = new Child();
        assertEquals(3.14, Reflect.getFieldValueDouble(c, "childDouble"), 1e-10);
    }

    @Test
    void getFieldValueDoubleMissing() {
        Child c = new Child();
        assertEquals(0.0, Reflect.getFieldValueDouble(c, "nonexistent"), 1e-10);
    }

    @Test
    void getFieldValueString() {
        Child c = new Child();
        assertEquals("child", Reflect.getFieldValueString(c, "childField"));
    }

    @Test
    void getFieldValueStringMissing() {
        Child c = new Child();
        assertNull(Reflect.getFieldValueString(c, "nonexistent"));
    }

    @Test
    void getFieldValueBool() {
        Child c = new Child();
        assertTrue(Reflect.getFieldValueBool(c, "childBool"));
    }

    @Test
    void getFieldValueBoolMissing() {
        Child c = new Child();
        assertFalse(Reflect.getFieldValueBool(c, "nonexistent"));
    }

    // --- hasField ---

    @Test
    void hasFieldTrue() {
        Child c = new Child();
        assertTrue(Reflect.hasField(c, "childField"));
    }

    @Test
    void hasFieldInherited() {
        Child c = new Child();
        assertTrue(Reflect.hasField(c, "parentField"));
    }

    @Test
    void hasFieldFalse() {
        Child c = new Child();
        assertFalse(Reflect.hasField(c, "nonexistent"));
    }

    // --- is ---

    @Test
    void isExactMatch() {
        Child c = new Child();
        assertTrue(Reflect.is(c, "me.ender.ReflectTest$Child"));
    }

    @Test
    void isWrongClass() {
        Child c = new Child();
        assertFalse(Reflect.is(c, "me.ender.ReflectTest$Parent"));
    }

    @Test
    void isNull() {
        assertFalse(Reflect.is(null, "anything"));
    }

    // --- like ---

    @Test
    void likeContains() {
        Child c = new Child();
        assertTrue(Reflect.like(c, "Child"));
    }

    @Test
    void likeNoMatch() {
        Child c = new Child();
        assertFalse(Reflect.like(c, "NotAMatch"));
    }

    @Test
    void likeNull() {
        assertFalse(Reflect.like(null, "anything"));
    }

    // --- interfaces ---

    @Test
    void interfacesOnClass() {
        Class[] ifaces = Reflect.interfaces(java.util.ArrayList.class);
        assertTrue(ifaces.length > 0);
    }

    // --- getEnumSuperclass ---

    enum TestEnum { A, B, C }

    @Test
    void getEnumSuperclass() {
        Class c = Reflect.getEnumSuperclass(TestEnum.A.getClass());
        assertNotNull(c);
        assertTrue(c.isEnum());
    }

    @Test
    void getEnumSuperclassNonEnum() {
        assertNull(Reflect.getEnumSuperclass(String.class));
    }

    // --- invoke ---

    static class Invocable {
        public String greet(String name) {
            return "Hello " + name;
        }
    }

    @Test
    void invoke() {
        Invocable obj = new Invocable();
        Object result = Reflect.invoke(obj, "greet", "World");
        assertEquals("Hello World", result);
    }

    @Test
    void invokeNonexistent() {
        Invocable obj = new Invocable();
        assertNull(Reflect.invoke(obj, "nonexistent"));
    }

    // --- hasInterface ---

    @Test
    void hasInterfaceTrue() {
        assertTrue(Reflect.hasInterface("java.util.List", java.util.ArrayList.class));
    }

    @Test
    void hasInterfaceFalse() {
        assertFalse(Reflect.hasInterface("java.util.Map", java.util.ArrayList.class));
    }
}
