package org.thobe.indy.proxy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

class ReflectionUtils
{
    static RuntimeException unchecked( Throwable throwable )
    {
        if ( throwable instanceof Error )
        {
            throw (Error) throwable;
        }
        if ( throwable instanceof RuntimeException )
        {
            return (RuntimeException) throwable;
        }
        return new RuntimeException( "Unexpected exception", throwable );
    }

    static MethodHandle methodHandle( Class<?> definingClass, String methodName, Class<?>... parameters )
    {
        Method method;
        try
        {
            method = definingClass.getDeclaredMethod( methodName, parameters );
        }
        catch ( NoSuchMethodException e )
        {
            throw new LinkageError(
                    "Could not find method '" + methodName + "' on " + definingClass.getName(), e );
        }
        method.setAccessible( true );
        try
        {
            return MethodHandles.publicLookup().unreflect( method );
        }
        catch ( IllegalAccessException e )
        {
            throw new LinkageError(
                    "Could not unreflect method '" + methodName + "' on " + definingClass.getName(), e );
        }
    }
}
