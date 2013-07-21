package org.thobe.indy.proxy;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.explicitCastArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.throwException;
import static java.lang.invoke.MethodType.methodType;

public abstract class Bootstrap<STATE>
{
    final Class<STATE> stateType;

    public Bootstrap( Class<STATE> stateType )
    {
        this.stateType = stateType;
    }

    @SuppressWarnings("unused"/*called from bootstrap methods*/)
    public abstract CallSite bootstrap( Class<?> proxyClass, MethodHandles.Lookup lookup, String name,
                                        MethodType signature );

    protected final MethodHandle defaultImplementationOf( Class<?> proxyClass, MethodHandles.Lookup lookup, String name,
                                                          MethodType signature )
    {
        try
        {
            if ( name.equals( "toString" ) && equals( signature, String.class, stateType ) )
            {
                return explicitCastArguments(
                        lookup.findVirtual( Object.class, "toString", methodType( String.class ) ),
                        methodType( String.class, stateType ) );
            }
            if ( name.equals( "hashCode" ) && equals( signature, int.class, stateType ) )
            {
                return explicitCastArguments( lookup.findVirtual( Object.class, "hashCode", methodType( int.class ) ),
                                              methodType( int.class, stateType ) );
            }
            if ( name.equals( "equals" ) && equals( signature, boolean.class, stateType, Object.class ) )
            {
                return insertArguments(
                        explicitCastArguments( DEFAULT_EQUALS, methodType(
                                boolean.class, Class.class, MethodHandle.class, stateType, Object.class ) ),
                        0, proxyClass, lookup.findGetter( proxyClass, "state", stateType ) );
            }
            return null;
        }
        catch ( NoSuchMethodException | IllegalAccessException | NoSuchFieldException e )
        {
            throw new RuntimeException( e );
        }
    }

    private static boolean equals( MethodType signature, Class<?> returnType, Class<?>... parameterTypes )
    {
        return signature.returnType() == returnType && Arrays.equals( signature.parameterArray(), parameterTypes );
    }

    protected final MethodHandle unsupportedOperation( MethodType signature )
    {
        return dropArguments( throwException( signature.returnType(), UnsupportedOperationException.class ),
                              0, signature.parameterArray() );
    }

    private static final MethodHandle DEFAULT_EQUALS =
            helper( boolean.class, "defaultEquals", Class.class, MethodHandle.class, Object.class, Object.class );

    @SuppressWarnings("unused"/*called through method handle*/)
    private static boolean defaultEquals( Class<?> type, MethodHandle stateGetter, Object state, Object that )
    {
        try
        {
            return type.isInstance( that ) && state.equals( stateGetter.invoke( that ) );
        }
        catch ( Throwable throwable )
        {
            return false;
        }
    }

    private static MethodHandle helper( Class<?> returnType, String name, Class<?>... parameterTypes )
    {
        try
        {
            return lookup().findStatic( Bootstrap.class, name, methodType( returnType, parameterTypes ) );
        }
        catch ( NoSuchMethodException | IllegalAccessException e )
        {
            throw new LinkageError( "Could not find: " + name, e );
        }
    }
}
