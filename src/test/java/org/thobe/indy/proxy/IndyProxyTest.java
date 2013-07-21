package org.thobe.indy.proxy;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.Test;

import static java.lang.invoke.MethodHandles.lookup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.thobe.indy.proxy.IndyProxy.createProxyFactory;

public class IndyProxyTest
{
    @Test
    public void shouldCreteProxyInstance() throws Exception
    {
        // given
        IndyProxy<String, Object> factory = createProxyFactory( classLoader(), new SayHelloBootstrap(),
                                                                Interface1.class, Interface2.class );
        String state = "foo";

        // when
        Interface1 instance1 = (Interface1) factory.create( state );
        Interface2 instance2 = (Interface2) factory.create( state );

        // then
        assertTrue( "instance equals itself", instance1.equals( instance1 ) );
        assertTrue( "instance equals instance with same state", instance1.equals( instance2 ) );
        assertTrue( "instance equals instance with same state", instance2.equals( instance1 ) );
        assertFalse( "instance not equals state", instance1.equals( state ) );
        assertFalse( "instance not equals null", instance1.equals( null ) );
        assertEquals( "hashCode equals state hashCode", state.hashCode(), instance1.hashCode() );
        assertEquals( "hashCode equals state hashCode", state.hashCode(), instance2.hashCode() );
        assertEquals( "hello foo", instance1.sayHello() );
        assertEquals( "hello foo", instance2.sayHello() );
        assertEquals( "toString equals state toString", state, instance1.toString() );
    }

    private ClassLoader classLoader()
    {
        return getClass().getClassLoader();
    }

    interface Interface1
    {
        String sayHello();
    }

    interface Interface2
    {
        Object sayHello();
    }

    private static class SayHelloBootstrap extends Bootstrap<String>
    {
        SayHelloBootstrap()
        {
            super( String.class );
        }

        @Override
        public CallSite bootstrap( Class<?> proxyClass, MethodHandles.Lookup lookup, String name,
                                   MethodType signature )
        {
            MethodHandle impl = defaultImplementationOf( proxyClass, lookup, name, signature );
            if ( impl == null )
            {
                try
                {
                    impl = lookup().findStatic( getClass(), name, signature );
                }
                catch ( NoSuchMethodException | IllegalAccessException e )
                {
                    impl = unsupportedOperation( signature );
                }
            }
            return new ConstantCallSite( impl );
        }

        @SuppressWarnings("unused"/*the implementation of the interface method*/)
        static String sayHello( String state )
        {
            return "hello " + state;
        }
    }

    public static void main( String... args )
    {
        Interface1 reflectProxy = Interface1.class.cast( Proxy.newProxyInstance(
                IndyProxyTest.class.getClassLoader(), new Class[]{Interface1.class},
                new InvocationHandler()
                {
                    @Override
                    public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
                    {
                        if ( Object.class == method.getDeclaringClass() )
                        {
                            switch ( method.getName() )
                            {
                            case "hashCode":
                                return "foo".hashCode();
                            case "equals":
                                return proxy.getClass().isInstance( args[0] );
                            case "toString":
                                return "foo";
                            }
                        }
                        else
                        {
                            return SayHelloBootstrap.sayHello( "foo" );
                        }
                        throw new UnsupportedOperationException();
                    }
                } ) );
        Interface1 indyProxy = createProxyFactory( IndyProxyTest.class.getClassLoader(), new SayHelloBootstrap(),
                                                   Interface1.class ).create( "foo" );
        Interface1 noProxy = new Interface1()
        {
            @Override
            public String sayHello()
            {
                return SayHelloBootstrap.sayHello( "foo" );
            }
        };
        boolean skipReflect = Boolean.getBoolean( "skipReflect" );
        boolean skipIndy = Boolean.getBoolean( "skipIndy" );
        boolean skipDirect = Boolean.getBoolean( "skipDirect" );
        benchmark( reflectProxy, 10 );
        benchmark( indyProxy, 10 );
        for ( int iterations = 1_000_000; ; )
        {
            assertTrue( reflectProxy.equals( reflectProxy ) );
            assertTrue( indyProxy.equals( indyProxy ) );
            assertEquals( reflectProxy.hashCode(), indyProxy.hashCode() );
            assertEquals( reflectProxy.toString(), indyProxy.toString() );
            long reflectTime = 0, indyTime = 0, noTime = 0;
            if ( !skipReflect )
            {
                reflectTime = benchmark( reflectProxy, iterations );
            }
            if ( !skipIndy )
            {
                indyTime = benchmark( indyProxy, iterations );
            }
            if ( !skipDirect )
            {
                noTime = benchmark( noProxy, iterations );
            }
            System.out.printf( "reflectProxy:%.3fns/inv   indyProxy:%.3fns/inv   noProxy:%.3fns/inv%n",
                               reflectTime / (double) iterations, indyTime / (double) iterations,
                               noTime / (double) iterations );
        }
    }

    private static long benchmark( Interface1 proxy, int iterations )
    {
        long time = System.nanoTime();
        for ( int i = 0; i < iterations; i++ )
        {
            proxy.sayHello();
        }
        return System.nanoTime() - time;
    }
}
