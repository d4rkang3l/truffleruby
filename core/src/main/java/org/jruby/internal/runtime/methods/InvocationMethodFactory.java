/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 * 
 * Copyright (C) 2006 The JRuby Community <www.jruby.org>
 * Copyright (C) 2006 Ola Bini <ola@ologix.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.internal.runtime.methods;

import org.jruby.Ruby;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubyKernel;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.anno.JavaMethodDescriptor;
import org.jruby.anno.TypePopulator;
import org.jruby.compiler.impl.SkinnyMethodAdapter;
import org.jruby.exceptions.JumpException;
import org.jruby.exceptions.RaiseException;
import org.jruby.lexer.yacc.ISourcePosition;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.ArgumentType;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.MethodFactory;
import org.jruby.runtime.Signature;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ClassDefiningJRubyClassLoader;
import org.jruby.util.CodegenUtils;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static java.lang.System.out;
import static org.jruby.util.CodegenUtils.ci;
import static org.jruby.util.CodegenUtils.p;
import static org.jruby.util.CodegenUtils.params;
import static org.jruby.util.CodegenUtils.sig;

/**
 * In order to avoid the overhead with reflection-based method handles, this
 * MethodFactory uses ASM to generate tiny invoker classes. This allows for
 * better performance and more specialization per-handle than can be supported
 * via reflection. It also allows optimizing away many conditionals that can
 * be determined once ahead of time.
 *
 * When running in secured environments, this factory may not function. When
 * this can be detected, MethodFactory will fall back on the reflection-based
 * factory instead.
 *
 * @see org.jruby.runtime.MethodFactory
 */
public class InvocationMethodFactory extends MethodFactory implements Opcodes {

    private static final Logger LOG = LoggerFactory.getLogger("InvocationMethodFactory");

    private static final boolean DEBUG = false;

    /** The outward call signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject[].class));

    /** The outward call signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_BLOCK = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject[].class, Block.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_ZERO_BLOCK = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, Block.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_ZERO = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_ONE_BLOCK = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject.class, Block.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_ONE = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_TWO_BLOCK = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject.class, IRubyObject.class, Block.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_TWO = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject.class, IRubyObject.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_THREE_BLOCK = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject.class, IRubyObject.class, IRubyObject.class, Block.class));

    /** The outward arity-zero call-with-block signature for compiled Ruby method handles. */
    private final static String COMPILED_CALL_SIG_THREE = sig(IRubyObject.class,
            params(ThreadContext.class, IRubyObject.class, RubyModule.class, String.class, IRubyObject.class, IRubyObject.class, IRubyObject.class));

    /** The super constructor signature for Java-based method handles. */
    private final static String JAVA_SUPER_SIG = sig(Void.TYPE, params(RubyModule.class, Visibility.class));

    /** The lvar index of "this" */
    public static final int THIS_INDEX = 0;

    /** The lvar index of the passed-in ThreadContext */
    public static final int THREADCONTEXT_INDEX = 1;

    /** The lvar index of the method-receiving object */
    public static final int RECEIVER_INDEX = 2;

    /** The lvar index of the RubyClass being invoked against */
    public static final int CLASS_INDEX = 3;

    /** The lvar index method name being invoked */
    public static final int NAME_INDEX = 4;

    /** The lvar index of the method args on the call */
    public static final int ARGS_INDEX = 5;

    /** The lvar index of the passed-in Block on the call */
    public static final int BLOCK_INDEX = 6;

    /** The classloader to use for code loading */
    protected final ClassDefiningJRubyClassLoader classLoader;

    /** An object to sync against when loading classes, to avoid dups */
    protected final Object syncObject;

    /**
     * Whether this factory has seen undefined methods already. This is used to
     * detect likely method handle collisions when we expect to create a new
     * handle for each call.
     */
    private boolean seenUndefinedClasses = false;

    /**
     * Whether we've informed the user that we've seen undefined methods; this
     * is to avoid a flood of repetitive information.
     */
    private boolean haveWarnedUser = false;

    /**
     * Construct a new InvocationMethodFactory using the specified classloader
     * to load code. If the target classloader is not an instance of
     * JRubyClassLoader, it will be wrapped with one.
     *
     * @param classLoader The classloader to use, or to wrap if it is not a
     * JRubyClassLoader instance.
     */
    public InvocationMethodFactory(ClassLoader classLoader) {
        // use the given classloader as our sync, regardless of whether we wrap it
        this.syncObject = classLoader;

        if (classLoader instanceof ClassDefiningJRubyClassLoader) {
            this.classLoader = (ClassDefiningJRubyClassLoader)classLoader;
        } else {
            this.classLoader = new ClassDefiningJRubyClassLoader(classLoader);
        }
    }

    protected boolean safeFixedSignature(Signature signature) {
        return signature.isFixed() && signature.required() <= 3;
    }

    static class DescriptorInfo {
        private int min;
        private int max;
        private boolean frame;
        private boolean scope;
        private boolean rest;
        private boolean block;
        private String parameterDesc;

        private static final boolean RICH_NATIVE_METHOD_PARAMETERS = false;

        public DescriptorInfo(List<JavaMethodDescriptor> descs) {
            min = Integer.MAX_VALUE;
            max = 0;
            frame = false;
            scope = false;
            rest = false;
            block = false;
            boolean first = true;
            boolean lastBlock = false;

            for (JavaMethodDescriptor desc: descs) {
                // make sure we don't have some methods with blocks and others without
                // the handle generation logic can't handle such cases yet
                if (first) {
                    first = false;
                } else {
                    if (lastBlock != desc.hasBlock) {
                        throw new RuntimeException("Mismatched block parameters for method " + desc.declaringClassName + "." + desc.name);
                    }
                }
                lastBlock = desc.hasBlock;

                int specificArity = -1;
                if (desc.hasVarArgs) {
                    if (desc.optional == 0 && !desc.rest && desc.required == 0) {
                        throw new RuntimeException("IRubyObject[] args but neither of optional or rest specified for method " + desc.declaringClassName + "." + desc.name);
                    }
                    rest = true;
                    if (descs.size() == 1) {
                        min = -1;
                    }
                } else {
                    if (desc.optional == 0 && !desc.rest) {
                        if (desc.required == 0) {
                            // No required specified, check actual number of required args
                            if (desc.actualRequired <= 3) {
                                // actual required is less than 3, so we use specific arity
                                specificArity = desc.actualRequired;
                            } else {
                                // actual required is greater than 3, raise error (we don't support actual required > 3)
                                throw new RuntimeException("Invalid specific-arity number of arguments (" + desc.actualRequired + ") on method " + desc.declaringClassName + "." + desc.name);
                            }
                        } else if (desc.required >= 0 && desc.required <= 3) {
                            if (desc.actualRequired != desc.required) {
                                throw new RuntimeException("Specified required args does not match actual on method " + desc.declaringClassName + "." + desc.name);
                            }
                            specificArity = desc.required;
                        }
                    }

                    if (specificArity < min) {
                        min = specificArity;
                    }

                    if (specificArity > max) {
                        max = specificArity;
                    }
                }

                if (frame && !desc.anno.frame()) throw new RuntimeException("Unbalanced frame property on method " + desc.declaringClassName + "." + desc.name);
                if (scope && !desc.anno.scope()) throw new RuntimeException("Unbalanced scope property on method " + desc.declaringClassName + "." + desc.name);
                frame |= desc.anno.frame();
                scope |= desc.anno.scope();
                block |= desc.hasBlock;
            }

            // Core methods currently only show :req's for fixed-arity or a single
            // :rest if it's variable arity. I have filed a bug to improve this
            // (using the skipped logic below, when the time comes) but for now
            // we follow suit. See https://bugs.ruby-lang.org/issues/8088

            StringBuilder descBuilder = new StringBuilder();
            if (min == max) {
                int i = 0;
                for (; i < min; i++) {
                    if (i > 0) descBuilder.append(';');
                    descBuilder.append(ArgumentType.REQ);
                }
               // variable arity
            } else if (RICH_NATIVE_METHOD_PARAMETERS) {
                int i = 0;
                for (; i < min; i++) {
                    if (i > 0) descBuilder.append(';');
                    descBuilder.append(ArgumentType.REQ);
                }

                for (; i < max; i++) {
                    if (i > 0) descBuilder.append(';');
                    descBuilder.append(ArgumentType.ANONOPT);
                }

                if (rest) {
                    if (i > 0) descBuilder.append(';');
                    descBuilder.append(ArgumentType.ANONREST);
                }
            } else {
                descBuilder.append(ArgumentType.ANONREST);
            }

            parameterDesc = descBuilder.toString();
        }

        @Deprecated
        public boolean isBacktrace() {
            return false;
        }

        public boolean isFrame() {
            return frame;
        }

        public int getMax() {
            return max;
        }

        public int getMin() {
            return min;
        }

        public boolean isScope() {
            return scope;
        }

        public boolean isRest() {
            return rest;
        }

        public boolean isBlock() {
            return block;
        }

        public String getParameterDesc() {
            return parameterDesc;
        }
    }

    /**
     * Use code generation to provide a method handle based on an annotated Java
     * method.
     *
     * @see org.jruby.runtime.MethodFactory#getAnnotatedMethod
     */
    public DynamicMethod getAnnotatedMethod(RubyModule implementationClass, List<JavaMethodDescriptor> descs) {
        JavaMethodDescriptor desc1 = descs.get(0);
        JRubyMethod anno = desc1.anno;
        String javaMethodName = desc1.name;

        if (DEBUG) out.println("Binding multiple: " + desc1.declaringClassName + "." + javaMethodName);

        try {
            Class c = getAnnotatedMethodClass(descs);

            DescriptorInfo info = new DescriptorInfo(descs);
            if (DEBUG) out.println(" min: " + info.getMin() + ", max: " + info.getMax());

            JavaMethod ic = (JavaMethod)c.getConstructor(new Class[]{RubyModule.class, Visibility.class}).newInstance(new Object[]{implementationClass, desc1.anno.visibility()});

            TypePopulator.populateMethod(
                    ic,
                    Arity.optional().getValue(),
                    javaMethodName,
                    desc1.isStatic,
                    CallConfiguration.getCallConfigByAnno(anno),
                    desc1.anno.notImplemented(),
                    desc1.getDeclaringClass(),
                    desc1.name,
                    desc1.getReturnClass(),
                    desc1.getParameterClasses(),
                    CallConfiguration.getCallerCallConfigByAnno(anno));
            return ic;
        } catch(Exception e) {
            e.printStackTrace();
            throw implementationClass.getRuntime().newLoadError(e.getMessage());
        }
    }

    /**
     * Use code generation to provide a method handle based on an annotated Java
     * method. Return the resulting generated or loaded class.
     *
     * @see org.jruby.runtime.MethodFactory#getAnnotatedMethod
     */
    public Class getAnnotatedMethodClass(List<JavaMethodDescriptor> descs) throws Exception {
        JavaMethodDescriptor desc1 = descs.get(0);

        if (!Modifier.isPublic(desc1.getDeclaringClass().getModifiers())) {
            LOG.warn("warning: binding non-public class {}; reflected handles won't work", desc1.declaringClassName);
        }

        String javaMethodName = desc1.name;

        if (DEBUG) {
            if (descs.size() > 1) {
                out.println("Binding multiple: " + desc1.declaringClassName + "." + javaMethodName);
            } else {
                out.println("Binding single: " + desc1.declaringClassName + "." + javaMethodName);
            }
        }

        String generatedClassName = CodegenUtils.getAnnotatedBindingClassName(javaMethodName, desc1.declaringClassName, desc1.isStatic, desc1.actualRequired, desc1.optional, descs.size() > 1, desc1.anno.frame());
        if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
            // in debug mode we append _DBG to class name to force it to regenerate (or use pre-generated debug version)
            generatedClassName += "_DBG";
        }
        String generatedClassPath = generatedClassName.replace('.', '/');

        DescriptorInfo info = new DescriptorInfo(descs);

        Class superclass = determineSuperclass(info);

        Class c = tryClass(generatedClassName, desc1.getDeclaringClass(), superclass);
        if (c == null) {
            synchronized (syncObject) {
                // try again
                c = tryClass(generatedClassName, desc1.getDeclaringClass(), superclass);
                if (c == null) {
                    if (DEBUG) out.println("Generating " + generatedClassName + ", min: " + info.getMin() + ", max: " + info.getMax() + ", hasBlock: " + info.isBlock() + ", rest: " + info.isRest());

                    String superClassString = p(superclass);

                    ClassWriter cw = createJavaMethodCtor(generatedClassPath, superClassString, info.getParameterDesc());

                    addAnnotatedMethodInvoker(cw, "call", superClassString, descs);

                    c = endClass(cw, generatedClassName);
                }
            }
        }

        return c;
    }

    private Class determineSuperclass(DescriptorInfo info) {
        Class superClass;
        if (info.getMin() == -1) {
            // normal all-rest method
            if (info.isBlock()) {
                superClass = JavaMethod.JavaMethodNBlock.class;
            } else {
                superClass = JavaMethod.JavaMethodN.class;
            }
        } else {
            if (info.isRest()) {
                if (info.isBlock()) {
                    superClass = JavaMethod.BLOCK_REST_METHODS[info.getMin()][info.getMax()];
                } else {
                    superClass = JavaMethod.REST_METHODS[info.getMin()][info.getMax()];
                }
            } else {
                if (info.isBlock()) {
                    superClass = JavaMethod.BLOCK_METHODS[info.getMin()][info.getMax()];
                } else {
                    superClass = JavaMethod.METHODS[info.getMin()][info.getMax()];
                }
            }
        }

        if (superClass == null) throw new RuntimeException("invalid multi combination");
        return superClass;
    }

    /**
     * Use code generation to provide a method handle based on an annotated Java
     * method.
     *
     * @see org.jruby.runtime.MethodFactory#getAnnotatedMethod
     */
    public DynamicMethod getAnnotatedMethod(RubyModule implementationClass, JavaMethodDescriptor desc) {
        String javaMethodName = desc.name;

        try {
            Class c = getAnnotatedMethodClass(Arrays.asList(desc));

            JavaMethod ic = (JavaMethod)c.getConstructor(new Class[]{RubyModule.class, Visibility.class}).newInstance(new Object[]{implementationClass, desc.anno.visibility()});

            TypePopulator.populateMethod(
                    ic,
                    Arity.fromAnnotation(desc.anno, desc.actualRequired).getValue(),
                    javaMethodName,
                    desc.isStatic,
                    CallConfiguration.getCallConfigByAnno(desc.anno),
                    desc.anno.notImplemented(),
                    desc.getDeclaringClass(),
                    desc.name,
                    desc.getReturnClass(),
                    desc.getParameterClasses(),
                    CallConfiguration.getCallerCallConfigByAnno(desc.anno));
            return ic;
        } catch(Exception e) {
            e.printStackTrace();
            throw implementationClass.getRuntime().newLoadError(e.getMessage());
        }
    }

    /**
     * Emit code to check the arity of a call to a Java-based method.
     *
     * @param jrubyMethod The annotation of the called method
     * @param method The code generator for the handle being created
     */
    private void checkArity(JRubyMethod jrubyMethod, SkinnyMethodAdapter method, int specificArity) {
        Label arityError = new Label();
        Label noArityError = new Label();

        switch (specificArity) {
        case 0:
        case 1:
        case 2:
        case 3:
            // for zero, one, two, three arities, JavaMethod.JavaMethod*.call(...IRubyObject[] args...) will check
            return;
        default:
            boolean checkArity = false;
            if (jrubyMethod.rest()) {
                if (jrubyMethod.required() > 0) {
                    // just confirm minimum args provided
                    method.aload(ARGS_INDEX);
                    method.arraylength();
                    method.ldc(jrubyMethod.required());
                    method.if_icmplt(arityError);
                    checkArity = true;
                }
            } else if (jrubyMethod.optional() > 0) {
                if (jrubyMethod.required() > 0) {
                    // confirm minimum args provided
                    method.aload(ARGS_INDEX);
                    method.arraylength();
                    method.ldc(jrubyMethod.required());
                    method.if_icmplt(arityError);
                }

                // confirm maximum not greater than optional
                method.aload(ARGS_INDEX);
                method.arraylength();
                method.ldc(jrubyMethod.required() + jrubyMethod.optional());
                method.if_icmpgt(arityError);
                checkArity = true;
            } else {
                // just confirm args length == required
                method.aload(ARGS_INDEX);
                method.arraylength();
                method.ldc(jrubyMethod.required());
                method.if_icmpne(arityError);
                checkArity = true;
            }

            if (checkArity) {
                method.go_to(noArityError);

                // Raise an error if arity does not match requirements
                method.label(arityError);
                method.aload(THREADCONTEXT_INDEX);
                method.invokevirtual(p(ThreadContext.class), "getRuntime", sig(Ruby.class));
                method.aload(ARGS_INDEX);
                method.ldc(jrubyMethod.required());
                method.ldc(jrubyMethod.required() + jrubyMethod.optional());
                method.invokestatic(p(Arity.class), "checkArgumentCount", sig(int.class, Ruby.class, IRubyObject[].class, int.class, int.class));
                method.pop();

                method.label(noArityError);
            }
        }
    }

    private ClassWriter createJavaMethodCtor(String namePath, String sup, String parameterDesc) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String sourceFile = namePath.substring(namePath.lastIndexOf('/') + 1) + ".gen";
        cw.visit(RubyInstanceConfig.JAVA_VERSION, ACC_PUBLIC + ACC_SUPER, namePath, null, sup, null);
        cw.visitSource(sourceFile, null);
        SkinnyMethodAdapter mv = new SkinnyMethodAdapter(cw, ACC_PUBLIC, "<init>", JAVA_SUPER_SIG, null, null);
        mv.start();
        mv.aloadMany(0, 1, 2);
        mv.visitMethodInsn(INVOKESPECIAL, sup, "<init>", JAVA_SUPER_SIG);
        mv.aload(0);
        mv.ldc(parameterDesc);
        mv.invokevirtual(p(JavaMethod.class), "setParameterDesc", sig(void.class, String.class));
        mv.voidreturn();
        mv.end();

        return cw;
    }

    private void invokeCallConfigPre(SkinnyMethodAdapter mv, String superClass, int specificArity, boolean block, CallConfiguration callConfig) {
        // invoke pre method stuff
        if (callConfig.isNoop()) return;

        prepareForPre(mv, specificArity, block, callConfig);
        mv.invokevirtual(superClass, getPreMethod(callConfig), getPreSignature(callConfig));
    }

    private void invokeCallConfigPost(SkinnyMethodAdapter mv, String superClass, CallConfiguration callConfig) {
        if (callConfig.isNoop()) return;

        mv.aload(1);
        mv.invokestatic(superClass, getPostMethod(callConfig), sig(void.class, params(ThreadContext.class)));
    }

    private void prepareForPre(SkinnyMethodAdapter mv, int specificArity, boolean block, CallConfiguration callConfig) {
        if (callConfig.isNoop()) return;

        mv.aloadMany(0, THREADCONTEXT_INDEX);

        switch (callConfig.framing()) {
        case Full:
            mv.aloadMany(RECEIVER_INDEX, NAME_INDEX); // self, name
            loadBlockForPre(mv, specificArity, block);
            break;
        case Backtrace:
            mv.aload(NAME_INDEX); // name
            break;
        case None:
            break;
        default: throw new RuntimeException("Unknown call configuration");
        }
    }

    private String getPreMethod(CallConfiguration callConfig) {
        switch (callConfig) {
        case FrameFullScopeFull: return "preFrameAndScope";
        case FrameFullScopeDummy: return "preFrameAndDummyScope";
        case FrameFullScopeNone: return "preFrameOnly";
        case FrameBacktraceScopeFull: return "preBacktraceAndScope";
        case FrameBacktraceScopeDummy: return "preBacktraceDummyScope";
        case FrameBacktraceScopeNone:  return "preBacktraceOnly";
        case FrameNoneScopeFull: return "preScopeOnly";
        case FrameNoneScopeDummy: return "preNoFrameDummyScope";
        case FrameNoneScopeNone: return "preNoop";
        default: throw new RuntimeException("Unknown call configuration");
        }
    }

    private String getPreSignature(CallConfiguration callConfig) {
        switch (callConfig) {
        case FrameFullScopeFull: return sig(void.class, params(ThreadContext.class, IRubyObject.class, String.class, Block.class));
        case FrameFullScopeDummy: return sig(void.class, params(ThreadContext.class, IRubyObject.class, String.class, Block.class));
        case FrameFullScopeNone: return sig(void.class, params(ThreadContext.class, IRubyObject.class, String.class, Block.class));
        case FrameBacktraceScopeFull: return sig(void.class, params(ThreadContext.class, String.class));
        case FrameBacktraceScopeDummy: return sig(void.class, params(ThreadContext.class, String.class));
        case FrameBacktraceScopeNone:  return sig(void.class, params(ThreadContext.class, String.class));
        case FrameNoneScopeFull: return sig(void.class, params(ThreadContext.class));
        case FrameNoneScopeDummy: return sig(void.class, params(ThreadContext.class));
        case FrameNoneScopeNone: return sig(void.class);
        default: throw new RuntimeException("Unknown call configuration");
        }
    }

    public static String getPostMethod(CallConfiguration callConfig) {
        switch (callConfig) {
        case FrameFullScopeFull: return "postFrameAndScope";
        case FrameFullScopeDummy: return "postFrameAndScope";
        case FrameFullScopeNone: return "postFrameOnly";
        case FrameBacktraceScopeFull: return "postBacktraceAndScope";
        case FrameBacktraceScopeDummy: return "postBacktraceDummyScope";
        case FrameBacktraceScopeNone:  return "postBacktraceOnly";
        case FrameNoneScopeFull: return "postScopeOnly";
        case FrameNoneScopeDummy: return "postNoFrameDummyScope";
        case FrameNoneScopeNone: return "postNoop";
        default: throw new RuntimeException("Unknown call configuration");
        }
    }

    private void loadArguments(SkinnyMethodAdapter mv, JavaMethodDescriptor desc, int specificArity) {
        switch (specificArity) {
        default:
        case -1:
            mv.aload(ARGS_INDEX);
            break;
        case 0:
            // no args
            break;
        case 1:
            loadArgumentWithCast(mv, 1, desc.argumentTypes[0]);
            break;
        case 2:
            loadArgumentWithCast(mv, 1, desc.argumentTypes[0]);
            loadArgumentWithCast(mv, 2, desc.argumentTypes[1]);
            break;
        case 3:
            loadArgumentWithCast(mv, 1, desc.argumentTypes[0]);
            loadArgumentWithCast(mv, 2, desc.argumentTypes[1]);
            loadArgumentWithCast(mv, 3, desc.argumentTypes[2]);
            break;
        }
    }

    private void loadArgumentWithCast(SkinnyMethodAdapter mv, int argNumber, Class coerceType) {
        mv.aload(ARGS_INDEX + (argNumber - 1));
        if (coerceType != IRubyObject.class && coerceType != IRubyObject[].class) {
            if (coerceType == RubyString.class) {
                mv.invokeinterface(p(IRubyObject.class), "convertToString", sig(RubyString.class));
            } else {
                throw new RuntimeException("Unknown coercion target: " + coerceType);
            }
        }
    }

    /** load block argument for pre() call.  Since we have fixed-arity call
     * paths we need calculate where the last var holding the block is.
     *
     * is we don't have a block we setup NULL_BLOCK as part of our null pattern
     * strategy (we do not allow null in any field which accepts block).
     */
    private void loadBlockForPre(SkinnyMethodAdapter mv, int specificArity, boolean getsBlock) {
        if (!getsBlock) {            // No block so load null block instance
            mv.getstatic(p(Block.class), "NULL_BLOCK", ci(Block.class));
            return;
        }

        loadBlock(mv, specificArity, getsBlock);
    }

    /** load the block argument from the correct position.  Since we have fixed-
     * arity call paths we need to calculate where the last var holding the
     * block is.
     *
     * If we don't have a block then this does nothing.
     */
    private void loadBlock(SkinnyMethodAdapter mv, int specificArity, boolean getsBlock) {
        if (!getsBlock) return;         // No block so nothing more to do

        switch (specificArity) {        // load block since it accepts a block
        case 0: case 1: case 2: case 3: // Fixed arities signatures
            mv.aload(BLOCK_INDEX - 1 + specificArity);
            break;
        default: case -1:
            mv.aload(BLOCK_INDEX);      // Generic arity signature
            break;
        }
    }

    private void loadReceiver(String typePath, JavaMethodDescriptor desc, SkinnyMethodAdapter mv) {
        // load target for invocations
        if (Modifier.isStatic(desc.modifiers)) {
            if (desc.hasContext) {
                mv.aload(THREADCONTEXT_INDEX);
            }

            // load self object as IRubyObject, for recv param
            mv.aload(RECEIVER_INDEX);
        } else {
            // load receiver as original type for virtual invocation
            mv.aload(RECEIVER_INDEX);
            mv.checkcast(typePath);

            if (desc.hasContext) {
                mv.aload(THREADCONTEXT_INDEX);
            }
        }
    }

    private Class tryClass(String name, Class targetClass, Class expectedSuperclass) {
        Class c;
        try {
            if (classLoader == null) {
                c = Class.forName(name, true, classLoader);
            } else {
                c = classLoader.loadClass(name);
            }
        } catch(Exception e) {
            seenUndefinedClasses = true;
            return null;
        }

        // For JRUBY-5038, ensure loaded class has superclass from same classloader as current JRuby
        if (c.getSuperclass() == expectedSuperclass) {
            if (seenUndefinedClasses && !haveWarnedUser) {
                haveWarnedUser = true;
                System.err.println("WARNING: while creating new bindings for " + targetClass + ",\n" +
                        "found an existing binding; you may want to run a clean build.");
            }

            return c;
        } else {
            seenUndefinedClasses = true;
            return null;
        }
    }

    protected Class endClass(ClassWriter cw, String name) {
        cw.visitEnd();
        byte[] code = cw.toByteArray();
        if (DEBUG) CheckClassAdapter.verify(new ClassReader(code), false, new PrintWriter(System.err));

        return classLoader.defineClass(name, code);
    }

    private SkinnyMethodAdapter beginMethod(ClassWriter cw, String methodName, int specificArity, boolean block) {
        switch (specificArity) {
        default:
        case -1:
            if (block) {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_BLOCK, null, null);
            } else {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG, null, null);
            }
        case 0:
            if (block) {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_ZERO_BLOCK, null, null);
            } else {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_ZERO, null, null);
            }
        case 1:
            if (block) {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_ONE_BLOCK, null, null);
            } else {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_ONE, null, null);
            }
        case 2:
            if (block) {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_TWO_BLOCK, null, null);
            } else {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_TWO, null, null);
            }
        case 3:
            if (block) {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_THREE_BLOCK, null, null);
            } else {
                return new SkinnyMethodAdapter(cw, ACC_PUBLIC, methodName, COMPILED_CALL_SIG_THREE, null, null);
            }
        }
    }

    private void addAnnotatedMethodInvoker(ClassWriter cw, String callName, String superClass, List<JavaMethodDescriptor> descs) {
        for (JavaMethodDescriptor desc: descs) {
            int specificArity = -1;
            if (desc.optional == 0 && !desc.rest) {
                if (desc.required == 0) {
                    if (desc.actualRequired <= 3) {
                        specificArity = desc.actualRequired;
                    } else {
                        specificArity = -1;
                    }
                } else if (desc.required >= 0 && desc.required <= 3) {
                    specificArity = desc.required;
                }
            }

            boolean hasBlock = desc.hasBlock;
            SkinnyMethodAdapter mv = null;

            mv = beginMethod(cw, callName, specificArity, hasBlock);
            mv.visitCode();

            createAnnotatedMethodInvocation(desc, mv, superClass, specificArity, hasBlock);

            mv.end();
        }
    }

    private void createAnnotatedMethodInvocation(JavaMethodDescriptor desc, SkinnyMethodAdapter method, String superClass, int specificArity, boolean block) {
        String typePath = desc.declaringClassPath;
        String javaMethodName = desc.name;

        checkArity(desc.anno, method, specificArity);

        CallConfiguration callConfig = CallConfiguration.getCallConfigByAnno(desc.anno);
        if (!callConfig.isNoop()) {
            invokeCallConfigPre(method, superClass, specificArity, block, callConfig);
        }

        int traceBoolIndex = -1;
        if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
            // load and store trace enabled flag
            switch (specificArity) {
            case -1:
                traceBoolIndex = ARGS_INDEX + (block ? 1 : 0) + 1;
                break;
            case 0:
                traceBoolIndex = ARGS_INDEX + (block ? 1 : 0);
                break;
            default:
                traceBoolIndex = ARGS_INDEX + specificArity + (block ? 1 : 0) + 1;
            }

            method.aload(1);
            method.invokevirtual(p(ThreadContext.class), "getRuntime", sig(Ruby.class));
            method.invokevirtual(p(Ruby.class), "hasEventHooks", sig(boolean.class));
            method.istore(traceBoolIndex);

            // call trace
            invokeCCallTrace(method, traceBoolIndex);
        }

        Label tryBegin = new Label();
        Label tryEnd = new Label();
        Label doFinally = new Label();

        if (!callConfig.isNoop()) {
            method.trycatch(tryBegin, tryEnd, doFinally, null);
        }

        method.label(tryBegin);
        {
            loadReceiver(typePath, desc, method);

            loadArguments(method, desc, specificArity);

            loadBlock(method, specificArity, block);

            if (Modifier.isStatic(desc.modifiers)) {
                // static invocation
                method.invokestatic(typePath, javaMethodName, desc.signature);
            } else {
                // virtual invocation
                method.invokevirtual(typePath, javaMethodName, desc.signature);
            }

            if (desc.getReturnClass() == void.class) {
                // void return type, so we need to load a nil for returning below
                method.aload(THREADCONTEXT_INDEX);
                method.getfield(p(ThreadContext.class), "nil", ci(IRubyObject.class));
            }
        }
        method.label(tryEnd);

        // normal finally and exit
        {
            if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
                invokeCReturnTrace(method, traceBoolIndex);
            }

            if (!callConfig.isNoop()) {
                invokeCallConfigPost(method, superClass, callConfig);
            }

            // return
            method.visitInsn(ARETURN);
        }

        // these are only needed if we have a non-noop call config
        if (!callConfig.isNoop()) {
            // finally handling for abnormal exit
            {
                method.label(doFinally);

                if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
                    invokeCReturnTrace(method, traceBoolIndex);
                }

                //call post method stuff (exception raised)
                if (!callConfig.isNoop()) {
                    invokeCallConfigPost(method, superClass, callConfig);
                }

                // rethrow exception
                method.athrow(); // rethrow it
            }
        }
    }

    private void invokeCCallTrace(SkinnyMethodAdapter method, int traceBoolIndex) {
        method.aloadMany(0, 1); // method, threadContext
        method.iload(traceBoolIndex); // traceEnable
        method.aload(4); // invokedName
        method.invokevirtual(p(JavaMethod.class), "callTrace", sig(void.class, ThreadContext.class, boolean.class, String.class));
    }

    private void invokeCReturnTrace(SkinnyMethodAdapter method, int traceBoolIndex) {
        method.aloadMany(0, 1); // method, threadContext
        method.iload(traceBoolIndex); // traceEnable
        method.aload(4); // invokedName
        method.invokevirtual(p(JavaMethod.class), "returnTrace", sig(void.class, ThreadContext.class, boolean.class, String.class));
    }
}
