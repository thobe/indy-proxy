package org.thobe.indy.proxy;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class MethodInfo
{
    public static final MethodInfo CONSTRUCTOR = new MethodInfo( void.class, "<init>", Object.class )
    {
        @Override
        void generateCode( String className, Class<?> stateType, MethodVisitor method, Handle bsm )
        {
            method.visitFieldInsn( Opcodes.GETSTATIC, className, "bootstrap",
                                   Type.getDescriptor( Bootstrap.class ) );
            Label ok = new Label();
            method.visitJumpInsn( Opcodes.IFNONNULL, ok );
            method.visitTypeInsn( Opcodes.NEW, Type.getInternalName( IllegalStateException.class ) );
            method.visitInsn( Opcodes.DUP );
            method.visitLdcInsn( "Bootstrap not assigned." );
            method.visitMethodInsn( Opcodes.INVOKESPECIAL, Type.getInternalName( IllegalStateException.class ),
                                    "<init>",
                                    Type.getMethodDescriptor( Type.VOID_TYPE, Type.getType( String.class ) ) );
            method.visitInsn( Opcodes.ATHROW );

            method.visitLabel( ok );
            method.visitVarInsn( Opcodes.ALOAD, 0 );
            method.visitMethodInsn( Opcodes.INVOKESPECIAL, Type.getInternalName( Object.class ), "<init>", "()V" );
            method.visitVarInsn( Opcodes.ALOAD, 0 );
            method.visitVarInsn( Opcodes.ALOAD, 1 );
            method.visitTypeInsn( Opcodes.CHECKCAST, Type.getInternalName( stateType ) );
            method.visitFieldInsn( Opcodes.PUTFIELD, className, "state", Type.getDescriptor( stateType ) );
            method.visitInsn( Opcodes.RETURN );

            method.visitMaxs( 3, 2 );
        }
    };
    private Class<?> returnType;
    private final String name;
    private final Class<?>[] parameterTypes;
    private final Set<Class> exceptions = new HashSet<>(), alternativeReturns = new HashSet<>();

    private MethodInfo( Class<?> returnType, String name, Class<?>... parameterTypes )
    {
        this.returnType = returnType;
        this.name = name;
        this.parameterTypes = parameterTypes;
        this.alternativeReturns.add( returnType );
    }

    private MethodInfo( Method method )
    {
        this( method.getReturnType(), method.getName(), method.getParameterTypes() );
        Collections.addAll( exceptions, method.getExceptionTypes() );
    }

    static void proxyMethod( Map<String, MethodInfo> methods, Method method )
    {
        String signature = signature( method );
        MethodInfo methodInfo = methods.get( signature );
        if ( methodInfo != null )
        {
            methodInfo.update( method );
        }
        else
        {
            methods.put( signature, new MethodInfo( method ) );
        }
    }

    private void update( Method method )
    {
        Class<?> ret = method.getReturnType();
        if ( returnType.isAssignableFrom( ret ) )
        {
            returnType = ret;
        }
        else if ( !ret.isAssignableFrom( returnType ) )
        {
            throw new IllegalArgumentException( "Incompatible return types." );
        }
        this.alternativeReturns.add( ret );
        Set<Class<?>> ex = new HashSet<>();
        Collections.addAll( ex, method.getExceptionTypes() );
        exceptions.retainAll( ex );
    }

    private static String signature( Method method )
    {
        StringBuilder sig = new StringBuilder( method.getName() ).append( '(' );
        for ( Class<?> param : method.getParameterTypes() )
        {
            sig.append( Type.getDescriptor( param ) );
        }
        return sig.append( ")" ).toString();
    }

    static MethodInfo constructor()
    {
        return CONSTRUCTOR;
    }

    static BootstrapMethod bootstrapMethod( String className, String methodName )
    {
        return new BootstrapMethod( className, methodName );
    }

    void generateMethod( String className, Class<?> stateType, ClassVisitor classVisitor, BootstrapMethod bsm )
    {
        MethodVisitor method = classVisitor.visitMethod( access(), name, desc(), null, exceptions() );
        method.visitCode();
        generateCode( className, stateType, method, bsm.handle );
        method.visitEnd();
        for ( Class returnType : alternativeReturns )
        {
            if ( returnType != this.returnType )
            {
                method = classVisitor.visitMethod( access() | Opcodes.ACC_BRIDGE, name,
                                                   Type.getMethodDescriptor( Type.getType( returnType ),
                                                                             types( parameterTypes ) ),
                                                   null, exceptions() );
                method.visitCode();
                generateBridge( className, method );
                method.visitEnd();
            }
        }
    }

    void generateCode( String className, Class<?> stateType, MethodVisitor method, Handle bsm )
    {
        method.visitVarInsn( Opcodes.ALOAD, 0 );
        method.visitFieldInsn( Opcodes.GETFIELD, className, "state", Type.getDescriptor( stateType ) );
        Type[] arguments = new Type[parameterTypes.length + 1];
        arguments[0] = Type.getType( stateType );
        loadAllParameters( method, arguments );
        method.visitInvokeDynamicInsn( name, Type.getMethodDescriptor( Type.getType( returnType ), arguments ), bsm );
        generateReturn( method );
        method.visitMaxs( parameterTypes.length + 1, parameterTypes.length + 1 );
    }

    private void generateBridge( String className, MethodVisitor method )
    {
        method.visitVarInsn( Opcodes.ALOAD, 0 );
        loadAllParameters( method, null );
        method.visitMethodInsn( Opcodes.INVOKEVIRTUAL, className, name, desc() );
        generateReturn( method );
        method.visitMaxs( parameterTypes.length + 1, parameterTypes.length + 1 );
    }

    private void loadAllParameters( MethodVisitor method, Type[] arguments )
    {
        for ( int i = 0; i < parameterTypes.length; i++ )
        {
            if ( arguments != null )
            {
                arguments[i + 1] = Type.getType( parameterTypes[i] );
            }
            if ( parameterTypes[i].isPrimitive() )
            {
                switch ( parameterTypes[i].getName() )
                {
                case "boolean":
                case "byte":
                case "short":
                case "char":
                case "int":
                    method.visitVarInsn( Opcodes.ILOAD, i + 1 );
                    break;
                case "long":
                    method.visitVarInsn( Opcodes.LLOAD, i + 1 );
                    break;
                case "float":
                    method.visitVarInsn( Opcodes.FLOAD, i + 1 );
                    break;
                case "double":
                    method.visitVarInsn( Opcodes.DLOAD, i + 1 );
                    break;
                default:
                    throw new IllegalStateException( "Unsupported primitive type:" + parameterTypes[i] );
                }
            }
            else
            {
                method.visitVarInsn( Opcodes.ALOAD, i + 1 );
            }
        }
    }

    private void generateReturn( MethodVisitor method )
    {
        if ( returnType.isPrimitive() )
        {
            switch ( returnType.getName() )
            {
            case "boolean":
            case "byte":
            case "short":
            case "char":
            case "int":
                method.visitInsn( Opcodes.IRETURN );
                break;
            case "long":
                method.visitInsn( Opcodes.LRETURN );
                break;
            case "float":
                method.visitInsn( Opcodes.FRETURN );
                break;
            case "double":
                method.visitInsn( Opcodes.DRETURN );
                break;
            default:
                throw new IllegalStateException( "Unsupported primitive type:" + returnType );
            }
        }
        else
        {
            method.visitInsn( Opcodes.ARETURN );
        }
    }

    int access()
    {
        return Opcodes.ACC_PUBLIC;
    }

    String desc()
    {
        return Type.getMethodDescriptor( Type.getType( returnType ), types( parameterTypes ) );
    }

    private static Type[] types( Class<?>... types )
    {
        Type[] result = new Type[types.length];
        for ( int i = 0; i < types.length; i++ )
        {
            result[i] = Type.getType( types[i] );
        }
        return result;
    }

    private String[] exceptions()
    {
        String[] exceptions = new String[this.exceptions.size()];
        Iterator<Class> iterator = this.exceptions.iterator();
        for ( int i = 0; i < exceptions.length; i++ )
        {
            exceptions[i] = Type.getInternalName( iterator.next() );
        }
        return exceptions;
    }

    static class BootstrapMethod extends MethodInfo
    {
        private final Handle handle;

        private BootstrapMethod( String className, String methodName )
        {
            super( CallSite.class, methodName, MethodHandles.Lookup.class, String.class, MethodType.class );
            this.handle = new Handle( Opcodes.H_INVOKESTATIC, className, methodName, desc() );
        }

        @Override
        int access()
        {
            return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
        }

        @Override
        void generateCode( String className, Class<?> stateType, MethodVisitor method, Handle bsm )
        {
            method.visitFieldInsn( Opcodes.GETSTATIC, className, "bootstrap", Type.getDescriptor( Bootstrap.class ) );
            method.visitLdcInsn( Type.getObjectType( className ) );
            method.visitVarInsn( Opcodes.ALOAD, 0 );
            method.visitVarInsn( Opcodes.ALOAD, 1 );
            method.visitVarInsn( Opcodes.ALOAD, 2 );
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, Type.getInternalName( Bootstrap.class ), "bootstrap",
                    Type.getMethodDescriptor(
                            Type.getType( CallSite.class ),
                            types( Class.class, MethodHandles.Lookup.class, String.class, MethodType.class ) ) );
            method.visitInsn( Opcodes.ARETURN );
            method.visitMaxs( 5, 3 );
        }
    }
}
