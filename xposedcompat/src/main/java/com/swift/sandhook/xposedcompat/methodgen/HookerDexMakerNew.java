package com.swift.sandhook.xposedcompat.methodgen;

import android.text.TextUtils;

import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Label;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;
import com.swift.sandhook.SandHook;
import com.swift.sandhook.wrapper.HookWrapper;
import com.swift.sandhook.xposedcompat.hookstub.HookStubManager;
import com.swift.sandhook.xposedcompat.utils.DexLog;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static com.swift.sandhook.xposedcompat.utils.DexMakerUtils.MD5;
import static com.swift.sandhook.xposedcompat.utils.DexMakerUtils.autoBoxIfNecessary;
import static com.swift.sandhook.xposedcompat.utils.DexMakerUtils.autoUnboxIfNecessary;
import static com.swift.sandhook.xposedcompat.utils.DexMakerUtils.createResultLocals;
import static com.swift.sandhook.xposedcompat.utils.DexMakerUtils.getObjTypeIdIfPrimitive;

public class HookerDexMakerNew implements HookMaker {

    public static final String METHOD_NAME_BACKUP = "backup";
    public static final String METHOD_NAME_HOOK = "hook";
    public static final TypeId<Object[]> objArrayTypeId = TypeId.get(Object[].class);
    private static final String CLASS_DESC_PREFIX = "L";
    private static final String CLASS_NAME_PREFIX = "SandHookerN";
    private static final String FIELD_NAME_HOOK_INFO = "additionalHookInfo";
    private static final String FIELD_NAME_METHOD = "method";
    private static final String FIELD_NAME_BACKUP_METHOD = "backupMethod";
    private static final TypeId<Throwable> throwableTypeId = TypeId.get(Throwable.class);
    private static final TypeId<Member> memberTypeId = TypeId.get(Member.class);
    private static final TypeId<Method> methodTypeId = TypeId.get(Method.class);
    private static final TypeId<XposedBridge.AdditionalHookInfo> hookInfoTypeId
            = TypeId.get(XposedBridge.AdditionalHookInfo.class);


    private FieldId<?, XposedBridge.AdditionalHookInfo> mHookInfoFieldId;
    private FieldId<?, Member> mMethodFieldId;
    private FieldId<?, Method> mBackupMethodFieldId;
    private MethodId<?, ?> mHookMethodId;
    private MethodId<?, ?> mBackupMethodId;
    private MethodId<?, ?> mSandHookBridgeMethodId;

    private TypeId<?> mHookerTypeId;
    private TypeId<?>[] mParameterTypeIds;
    private Class<?>[] mActualParameterTypes;
    private Class<?> mReturnType;
    private TypeId<?> mReturnTypeId;
    private boolean mIsStatic;
    // TODO use this to generate methods
    private boolean mHasThrowable;

    private DexMaker mDexMaker;
    private Member mMember;
    private XposedBridge.AdditionalHookInfo mHookInfo;
    private ClassLoader mAppClassLoader;
    private Class<?> mHookClass;
    private Method mHookMethod;
    private Method mBackupMethod;
    private String mDexDirPath;

    private static TypeId<?>[] getParameterTypeIds(Class<?>[] parameterTypes, boolean isStatic) {
        int parameterSize = parameterTypes.length;
        int targetParameterSize = isStatic ? parameterSize : parameterSize + 1;
        TypeId<?>[] parameterTypeIds = new TypeId<?>[targetParameterSize];
        int offset = 0;
        if (!isStatic) {
            parameterTypeIds[0] = TypeId.OBJECT;
            offset = 1;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypeIds[i + offset] = TypeId.get(parameterTypes[i]);
        }
        return parameterTypeIds;
    }

    private static Class<?>[] getParameterTypes(Class<?>[] parameterTypes, boolean isStatic) {
        if (isStatic) {
            return parameterTypes;
        }
        int parameterSize = parameterTypes.length;
        int targetParameterSize = parameterSize + 1;
        Class<?>[] newParameterTypes = new Class<?>[targetParameterSize];
        int offset = 1;
        newParameterTypes[0] = Object.class;
        System.arraycopy(parameterTypes, 0, newParameterTypes, offset, parameterTypes.length);
        return newParameterTypes;
    }

    public void start(Member member, XposedBridge.AdditionalHookInfo hookInfo,
                      ClassLoader appClassLoader, String dexDirPath) throws Exception {
        if (member instanceof Method) {
            Method method = (Method) member;
            mIsStatic = Modifier.isStatic(method.getModifiers());
            mReturnType = method.getReturnType();
            if (mReturnType.equals(Void.class) || mReturnType.equals(void.class)
                    || mReturnType.isPrimitive()) {
                mReturnTypeId = TypeId.get(mReturnType);
            } else {
                // all others fallback to plain Object for convenience
                mReturnType = Object.class;
                mReturnTypeId = TypeId.OBJECT;
            }
            mParameterTypeIds = getParameterTypeIds(method.getParameterTypes(), mIsStatic);
            mActualParameterTypes = getParameterTypes(method.getParameterTypes(), mIsStatic);
            mHasThrowable = method.getExceptionTypes().length > 0;
        } else if (member instanceof Constructor) {
            Constructor constructor = (Constructor) member;
            mIsStatic = false;
            mReturnType = void.class;
            mReturnTypeId = TypeId.VOID;
            mParameterTypeIds = getParameterTypeIds(constructor.getParameterTypes(), mIsStatic);
            mActualParameterTypes = getParameterTypes(constructor.getParameterTypes(), mIsStatic);
            mHasThrowable = constructor.getExceptionTypes().length > 0;
        } else if (member.getDeclaringClass().isInterface()) {
            throw new IllegalArgumentException("Cannot hook interfaces: " + member.toString());
        } else if (Modifier.isAbstract(member.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + member.toString());
        } else {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + member.toString());
        }
        mMember = member;
        mHookInfo = hookInfo;
        mDexDirPath = dexDirPath;
        if (appClassLoader == null
                || appClassLoader.getClass().getName().equals("java.lang.BootClassLoader")) {
            mAppClassLoader = this.getClass().getClassLoader();
        } else {
            mAppClassLoader = appClassLoader;
        }

        mDexMaker = new DexMaker();
        // Generate a Hooker class.
        String className = getClassName(mMember);
        String dexName = className + ".jar";

        HookWrapper.HookEntity hookEntity = null;
        //try load cache first
        try {
            ClassLoader loader = mDexMaker.loadClassDirect(mAppClassLoader, new File(mDexDirPath), dexName);
            if (loader != null) {
                hookEntity = loadHookerClass(loader, className);
            }
        } catch (Throwable throwable) {}

        //do generate
        if (hookEntity == null) {
            hookEntity = doMake(className, dexName);
        }
        SandHook.hook(hookEntity);
    }

    private HookWrapper.HookEntity doMake(String className, String dexName) throws Exception {
        mHookerTypeId = TypeId.get(CLASS_DESC_PREFIX + className + ";");
        mDexMaker.declare(mHookerTypeId, className + ".generated", Modifier.PUBLIC, TypeId.OBJECT);
        generateFields();
        generateHookMethod();
        generateBackupMethod();

        ClassLoader loader;
        if (TextUtils.isEmpty(mDexDirPath)) {
            throw new IllegalArgumentException("dexDirPath should not be empty!!!");
        }
        // Create the dex file and load it.
        loader = mDexMaker.generateAndLoad(mAppClassLoader, new File(mDexDirPath), dexName);
        return loadHookerClass(loader, className);
    }

    private HookWrapper.HookEntity loadHookerClass(ClassLoader loader, String className) throws Exception {
        mHookClass = loader.loadClass(className);
        // Execute our newly-generated code in-process.
        mHookMethod = mHookClass.getMethod(METHOD_NAME_HOOK, mActualParameterTypes);
        mBackupMethod = mHookClass.getMethod(METHOD_NAME_BACKUP, mActualParameterTypes);
        setup(mHookClass);
        return new HookWrapper.HookEntity(mMember, mHookMethod, mBackupMethod, false);
    }

    private void setup(Class mHookClass) {
        XposedHelpers.setStaticObjectField(mHookClass, FIELD_NAME_METHOD, mMember);
        XposedHelpers.setStaticObjectField(mHookClass, FIELD_NAME_BACKUP_METHOD, mBackupMethod);
        XposedHelpers.setStaticObjectField(mHookClass, FIELD_NAME_HOOK_INFO, mHookInfo);
    }

    private String getClassName(Member originMethod) {
        return CLASS_NAME_PREFIX + "_" + MD5(originMethod.toString());
    }

    public Method getHookMethod() {
        return mHookMethod;
    }

    public Method getBackupMethod() {
        return mBackupMethod;
    }

    public Method getCallBackupMethod() {
        return mBackupMethod;
    }

    public Class getHookClass() {
        return mHookClass;
    }

    private void generateFields() {
        mHookInfoFieldId = mHookerTypeId.getField(hookInfoTypeId, FIELD_NAME_HOOK_INFO);
        mMethodFieldId = mHookerTypeId.getField(memberTypeId, FIELD_NAME_METHOD);
        mBackupMethodFieldId = mHookerTypeId.getField(methodTypeId, FIELD_NAME_BACKUP_METHOD);
        mDexMaker.declare(mHookInfoFieldId, Modifier.STATIC, null);
        mDexMaker.declare(mMethodFieldId, Modifier.STATIC, null);
        mDexMaker.declare(mBackupMethodFieldId, Modifier.STATIC, null);
    }

    private void generateBackupMethod() {
        mBackupMethodId = mHookerTypeId.getMethod(mReturnTypeId, METHOD_NAME_BACKUP, mParameterTypeIds);
        Code code = mDexMaker.declare(mBackupMethodId, Modifier.PUBLIC | Modifier.STATIC);

        Local<Member> method = code.newLocal(memberTypeId);

        Map<TypeId, Local> resultLocals = createResultLocals(code);
        MethodId<?, ?> errLogMethod = TypeId.get(DexLog.class).getMethod(TypeId.get(Void.TYPE), "printCallOriginError", memberTypeId);


        //very very important!!!!!!!!!!!
        //add a try cache block avoid inline
        Label tryCatchBlock = new Label();

        code.addCatchClause(throwableTypeId, tryCatchBlock);
        code.sget(mMethodFieldId, method);
        code.invokeStatic(errLogMethod, null, method);
        // start of try
        code.mark(tryCatchBlock);

        // do nothing
        if (mReturnTypeId.equals(TypeId.VOID)) {
            code.returnVoid();
        } else {
            // we have limited the returnType to primitives or Object, so this should be safe
            code.returnValue(resultLocals.get(mReturnTypeId));
        }
    }

    private void generateHookMethod() {
        mHookMethodId = mHookerTypeId.getMethod(mReturnTypeId, METHOD_NAME_HOOK, mParameterTypeIds);
        mSandHookBridgeMethodId = TypeId.get(HookStubManager.class).getMethod(TypeId.get(Object.class), "hookBridge", memberTypeId, TypeId.get(Object.class), TypeId.get(Object[].class));

        Code code = mDexMaker.declare(mHookMethodId, Modifier.PUBLIC | Modifier.STATIC);

        Local<Member> method = code.newLocal(memberTypeId);
 //       Local<Method> backupMethod = code.newLocal(methodTypeId);
        Local<Object> thisObject = code.newLocal(TypeId.OBJECT);
        Local<Object[]> args = code.newLocal(objArrayTypeId);
        Local<Integer> actualParamSize = code.newLocal(TypeId.INT);
        Local<Integer> argIndex = code.newLocal(TypeId.INT);
        Local<Object> resultObj = code.newLocal(TypeId.OBJECT);

        Local[] allArgsLocals = createParameterLocals(code);
        Map<TypeId, Local> resultLocals = createResultLocals(code);


        code.sget(mMethodFieldId, method);
        code.loadConstant(args, null);
        code.loadConstant(argIndex, 0);
//        code.sget(mBackupMethodFieldId, backupMethod);
        int paramsSize = mParameterTypeIds.length;
        int offset = 0;
        // thisObject
        if (mIsStatic) {
            // thisObject = null
            code.loadConstant(thisObject, null);
        } else {
            // thisObject = args[0]
            offset = 1;
            code.move(thisObject, allArgsLocals[0]);
        }

        // actual args (exclude thisObject if this is not a static method)
        code.loadConstant(actualParamSize, paramsSize - offset);
        code.newArray(args, actualParamSize);
        for (int i = offset; i < paramsSize; i++) {
            Local parameter = allArgsLocals[i];
            // save parameter to resultObj as Object
            autoBoxIfNecessary(code, resultObj, parameter);
            code.loadConstant(argIndex, i - offset);
            // save Object to args
            code.aput(args, argIndex, resultObj);
        }

        if (mReturnTypeId.equals(TypeId.VOID)) {
            code.invokeStatic(mSandHookBridgeMethodId, null, method, thisObject, args);
            code.returnVoid();
        } else {
            code.invokeStatic(mSandHookBridgeMethodId, resultObj, method, thisObject, args);
            TypeId objTypeId = getObjTypeIdIfPrimitive(mReturnTypeId);
            Local matchObjLocal = resultLocals.get(objTypeId);
            code.cast(matchObjLocal, resultObj);
            // have to use matching typed Object(Integer, Double ...) to do unboxing
            Local toReturn = resultLocals.get(mReturnTypeId);
            autoUnboxIfNecessary(code, toReturn, matchObjLocal, resultLocals, true);
            code.returnValue(toReturn);
        }

    }

    private Local[] createParameterLocals(Code code) {
        Local[] paramLocals = new Local[mParameterTypeIds.length];
        for (int i = 0; i < mParameterTypeIds.length; i++) {
            paramLocals[i] = code.getParameter(i, mParameterTypeIds[i]);
        }
        return paramLocals;
    }
}