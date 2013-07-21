package org.thobe.indy.proxy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.CheckClassAdapter;

import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Collections.addAll;

import static org.thobe.indy.proxy.ReflectionUtils.methodHandle;

public class IndyProxy<S, T>
{
    @SafeVarargs
    public static <S, T> IndyProxy<S, T> createProxyFactory( ClassLoader loader, Bootstrap<S> bootstrap,
                                                             Class<? extends T>... interfaceTypes )
    {
        List<MethodInfo> methods = new ArrayList<>( generateMethods( interfaceTypes ) );
        methods.add( MethodInfo.constructor() );
        String name = proxyClassName( loader, setOf( bootstrap.stateType, interfaceTypes ) );
        byte[] byteCode = generateProxyClass( name.replace( '.', '/' ), bootstrap.stateType, methods, interfaceTypes );
        verify( loader, byteCode );
        Class<?> proxyClass = defineClass( loader, name, byteCode );
        setBootstrap( proxyClass, bootstrap );
        return new IndyProxy<>( constructorOf( proxyClass ) );
    }

    @SuppressWarnings({"ConstantConditions", "AssertWithSideEffects", "UnusedAssignment"})
    private static void verify( ClassLoader loader, byte[] byteCode )
    {
        boolean verify = false;
        assert verify = true;
        if ( verify )
        {
            StringWriter result = new StringWriter();
            CheckClassAdapter.verify( new ClassReader( byteCode ), loader, false, new PrintWriter( result ) );
            String errors = result.toString();
            if ( !errors.isEmpty() )
            {
                throw new IllegalStateException(
                        "Class verification failed:\n\t\t" + errors.replace( "\n", "\n\t\t" ).trim() );
            }
        }
    }

    @SafeVarargs
    private static <T> Collection<T> setOf( T first, T... more )
    {
        Collection<T> result = new ArrayList<>();
        result.add( first );
        addAll( result, more );
        return result;
    }

    private final MethodHandle constructor;

    @SuppressWarnings("unchecked")
    public T create( S state )
    {
        try
        {
            return (T) constructor.invoke( state );
        }
        catch ( Throwable throwable )
        {
            throw ReflectionUtils.unchecked( throwable );
        }
    }

    private IndyProxy( MethodHandle constructor )
    {
        this.constructor = constructor;
    }

    private static Method hashCodeMethod;
    private static Method equalsMethod;
    private static Method toStringMethod;

    static
    {
        try
        {
            hashCodeMethod = Object.class.getMethod( "hashCode" );
            equalsMethod = Object.class.getMethod( "equals", Object.class );
            toStringMethod = Object.class.getMethod( "toString" );
        }
        catch ( NoSuchMethodException e )
        {
            throw new NoSuchMethodError( e.getMessage() );
        }
    }

    private static Collection<MethodInfo> generateMethods( Class<?>... types )
    {
        Map<String, MethodInfo> methods = new HashMap<>();
        MethodInfo.proxyMethod( methods, hashCodeMethod );
        MethodInfo.proxyMethod( methods, equalsMethod );
        MethodInfo.proxyMethod( methods, toStringMethod );
        for ( Class<?> type : types )
        {
            for ( Method method : type.getMethods() )
            {
                MethodInfo.proxyMethod( methods, method );
            }
        }
        return methods.values();
    }

    private static String proxyClassName( ClassLoader loader, Collection<Class<?>> types )
    {
        String packageName = proxyPackageName( types );
        for ( int i = 0; ; i++ )
        {
            String className = packageName + "." + IndyProxy.class.getSimpleName() + "$" + i;
            try
            {
                loader.loadClass( className );
            }
            catch ( ClassNotFoundException e )
            {
                return className;
            }
        }
    }

    private static String proxyPackageName( Collection<Class<?>> types )
    {
        Set<String> packages = new HashSet<>(), hidden = new HashSet<>();
        for ( Class<?> type : types )
        {
            packages.add( type.getPackage().getName() );
            if ( !Modifier.isPublic( type.getModifiers() ) )
            {
                hidden.add( type.getPackage().getName() );
            }
        }
        if ( packages.size() == 1 )
        {
            return singleElement( packages );
        }
        else if ( hidden.isEmpty() )
        {
            return IndyProxy.class.getPackage().getName() + ".proxies";
        }
        else if ( hidden.size() == 1 )
        {
            return singleElement( hidden );
        }
        else
        {
            throw new IllegalArgumentException(
                    "Cannot proxy multiple interfaces with protected access from different packages." );
        }
    }

    private static <T> T singleElement( Collection<T> collection )
    {
        Iterator<T> iterator = collection.iterator();
        if ( iterator.hasNext() )
        {
            T element = iterator.next();
            if ( iterator.hasNext() )
            {
                throw new IllegalArgumentException( "Multiple elements" );
            }
            return element;
        }
        return null;
    }

    private static byte[] generateProxyClass( String name, Class<?> state, List<MethodInfo> methods, Class<?>... types )
    {
        ClassWriter cw = new ClassWriter( ClassWriter.COMPUTE_FRAMES );

        String[] interfaces = new String[types.length];
        for ( int i = 0; i < types.length; i++ )
        {
            interfaces[i] = Type.getInternalName( types[i] );
        }
        cw.visit( Opcodes.V1_7, Opcodes.ACC_PUBLIC, name, null, Type.getInternalName( Object.class ), interfaces );

        cw.visitField( Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "bootstrap", Type.getDescriptor( Bootstrap.class ),
                       null, null );
        cw.visitField( Opcodes.ACC_PRIVATE, "state", Type.getDescriptor( state ), null, null );

        MethodInfo.BootstrapMethod bsm = MethodInfo.bootstrapMethod( name, "bootstrap" );
        methods.add( bsm );
        for ( MethodInfo method : methods )
        {
            method.generateMethod( name, state, cw, bsm );
        }

        cw.visitEnd();

        return cw.toByteArray();
    }

    private static MethodHandle DEFINE_CLASS = methodHandle( ClassLoader.class, "defineClass",
                                                             String.class, byte[].class, int.class, int.class );

    private static Class<?> defineClass( ClassLoader loader, String name, byte[] byteCode )
    {
        try
        {
            return (Class<?>) DEFINE_CLASS.invoke( loader, name, byteCode, 0, byteCode.length );
        }
        catch ( Throwable throwable )
        {
            throw ReflectionUtils.unchecked( throwable );
        }
    }

    private static void setBootstrap( Class<?> proxyClass, Bootstrap bootstrap )
    {
        try
        {
            Field field = proxyClass.getDeclaredField( "bootstrap" );
            field.setAccessible( true );
            field.set( null, bootstrap );
        }
        catch ( NoSuchFieldException | IllegalAccessException e )
        {
            throw new LinkageError( "Could not set bootstrap.", e );
        }
    }

    private static MethodHandle constructorOf( Class<?> proxyClass )
    {
        try
        {
            return publicLookup().findConstructor( proxyClass, methodType( void.class, Object.class ) );
        }
        catch ( NoSuchMethodException | IllegalAccessException e )
        {
            throw new LinkageError( "Failed to get constructor.", e );
        }
    }
}
