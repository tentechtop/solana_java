package com.bit.solana.aop;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class PermissionsAspect {
    /**
     * token刷新时间
     */
    public static final long TOKEN_EXPIRE_TIME = 24 * 60 * 60;

    /**
     * 设置切入点 在注解的位置切入代码
     */
    @Pointcut("@annotation(com.bit.solana.aop.annotation.PermissionsAnnotation)")
    public void PermissionsPointCut() {}


    @Around(value = "PermissionsPointCut()")
    @SuppressWarnings("unchecked")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object[] args = pjp.getArgs();
        for (Object arg : args) {
            /*            System.out.println("Method parameter: " + arg);*/
        }
        return pjp.proceed();
    }

    @AfterReturning(value = "PermissionsPointCut()", returning = "keys")
    @SuppressWarnings("unchecked")
    public Object afterMethod(JoinPoint joinPoint, Object keys) {
        /*        System.out.println("方法执行后");*/

        return null;
    }
}
