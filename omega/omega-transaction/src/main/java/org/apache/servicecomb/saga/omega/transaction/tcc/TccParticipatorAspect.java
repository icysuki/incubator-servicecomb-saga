/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.saga.omega.transaction.tcc;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import org.apache.servicecomb.saga.common.TransactionStatus;
import org.apache.servicecomb.saga.omega.context.OmegaContext;
import org.apache.servicecomb.saga.omega.transaction.annotations.Participate;
import org.apache.servicecomb.saga.omega.transaction.tcc.events.ParticipatedEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Aspect
public class TccParticipatorAspect {
  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final OmegaContext context;
  private final TccEventService tccEventService;
  private final ParametersContext parametersContext;

  //We need to inject the CoordinateMessageHandler for the parameter map

  public TccParticipatorAspect(TccEventService tccEventService, OmegaContext context,
      ParametersContext parametersContext) {
    this.context = context;
    this.tccEventService = tccEventService;
    this.parametersContext = parametersContext;
  }

  @Around("execution(@org.apache.servicecomb.saga.omega.transaction.annotations.Participate * *(..)) && @annotation(participate)")
  Object advise(ProceedingJoinPoint joinPoint, Participate participate) throws Throwable {
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    String localTxId = context.localTxId();
    String cancelMethod = callbackMethodSignature(joinPoint, participate.cancelMethod(), method);
    String confirmMethod = callbackMethodSignature(joinPoint, participate.confirmMethod(), method);
    
    context.newLocalTxId();
    LOG.debug("Updated context {} for participate method {} ", context, method.toString());

    try {
      Object result = joinPoint.proceed();
      // Send the participate message back
      tccEventService.participate(new ParticipatedEvent(context.globalTxId(), context.localTxId(), localTxId, confirmMethod,
          cancelMethod, TransactionStatus.Succeed));
      // Just store the parameters into the context
      parametersContext.putParamters(context.localTxId(), joinPoint.getArgs());
      LOG.debug("Participate Transaction with context {} has finished.", context);
      return result;
    } catch (Throwable throwable) {
      // Now we don't handle the error message
      tccEventService.participate(new ParticipatedEvent(context.globalTxId(), context.localTxId(), localTxId, confirmMethod,
          cancelMethod, TransactionStatus.Failed));
      LOG.error("Participate Transaction with context {} failed.", context, throwable);
      throw throwable;
    } finally {
      context.setLocalTxId(localTxId);
    }
  }

  String callbackMethodSignature(ProceedingJoinPoint joinPoint, String callbackMethod, Method tryMethod) throws NoSuchMethodException {
    return callbackMethod.isEmpty() ? "" :
        joinPoint.getTarget()
        .getClass()
        .getDeclaredMethod(callbackMethod, tryMethod.getParameterTypes())
        .toString();
  }
}
