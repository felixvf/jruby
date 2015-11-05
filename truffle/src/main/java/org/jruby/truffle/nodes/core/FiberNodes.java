/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;

import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.cast.SingleValueCastNode;
import org.jruby.truffle.nodes.cast.SingleValueCastNodeGen;
import org.jruby.truffle.nodes.core.FiberNodesFactory.FiberTransferNodeFactory;
import org.jruby.truffle.nodes.methods.UnsupportedOperationBehavior;
import org.jruby.truffle.nodes.rubinius.ThreadPrimitiveNodes.ThreadRaisePrimitiveNode;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.BreakException;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.control.ReturnException;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.subsystems.ThreadManager;
import org.jruby.truffle.runtime.subsystems.ThreadManager.BlockingAction;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

@CoreClass(name = "Fiber")
public abstract class FiberNodes {

    public static DynamicObject createFiber(DynamicObject thread, DynamicObject rubyClass, String name) {
        return createFiber(thread, rubyClass, name, false);
    }

    public static DynamicObject createRootFiber(RubyContext context, DynamicObject thread) {
        return createFiber(thread, context.getCoreLibrary().getFiberClass(), "root Fiber for Thread", true);
    }

    private static DynamicObject createFiber(DynamicObject thread, DynamicObject rubyClass, String name, boolean isRootFiber) {
        assert RubyGuards.isRubyThread(thread);
        return Layouts.FIBER.createFiber(
                Layouts.CLASS.getInstanceFactory(rubyClass),
                isRootFiber,
                new CountDownLatch(1),
                new LinkedBlockingQueue<FiberMessage>(2),
                thread,
                null,
                true,
                null);
    }

    public static void initialize(final RubyContext context, final DynamicObject fiber, final DynamicObject block, final Node currentNode) {
        final String name = "Ruby Fiber@" + Layouts.PROC.getSharedMethodInfo(block).getSourceSection().getShortDescription();
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                handleFiberExceptions(context, fiber, block, currentNode);
            }
        });
        thread.setName(name);
        thread.start();

        waitForInitialization(context, fiber, currentNode);
    }

    /** Wait for full initialization of the new fiber */
    public static void waitForInitialization(RubyContext context, DynamicObject fiber, Node currentNode) {
        final CountDownLatch initializedLatch = Layouts.FIBER.getInitializedLatch(fiber);

        context.getThreadManager().runUntilSuccessKeepRunStatus(currentNode, new BlockingAction<Boolean>() {
            @Override
            public Boolean block() throws InterruptedException {
                initializedLatch.await();
                return SUCCESS;
            }
        });
    }

    private static void handleFiberExceptions(final RubyContext context, final DynamicObject fiber, final DynamicObject block, Node currentNode) {
        run(context, fiber, currentNode, new Runnable() {
            @Override
            public void run() {
                try {
                    final Object[] args = waitForResume(context, fiber);
                    final Object result;
                    try {
                        result = ProcNodes.rootCall(block, args);
                    } finally {
                        // Make sure that other fibers notice we are dead before they gain control back
                        Layouts.FIBER.setAlive(fiber, false);
                    }
                    resume(fiber, Layouts.FIBER.getLastResumedByFiber(fiber), true, result);
                } catch (FiberExitException e) {
                    assert !Layouts.FIBER.getRootFiber(fiber);
                    // Naturally exit the Java thread on catching this
                } catch (BreakException e) {
                    Layouts.FIBER.getMessageQueue(Layouts.FIBER.getLastResumedByFiber(fiber)).add(new FiberExceptionMessage(context.getCoreLibrary().breakFromProcClosure(null)));
                } catch (ReturnException e) {
                    Layouts.FIBER.getMessageQueue(Layouts.FIBER.getLastResumedByFiber(fiber)).add(new FiberExceptionMessage(context.getCoreLibrary().unexpectedReturn(null)));
                } catch (RaiseException e) {
                    Layouts.FIBER.getMessageQueue(Layouts.FIBER.getLastResumedByFiber(fiber)).add(new FiberExceptionMessage(e.getRubyException()));
                }
            }
        });
    }

    public static void run(RubyContext context, DynamicObject fiber, Node currentNode, final Runnable task) {
        assert RubyGuards.isRubyFiber(fiber);

        start(context, fiber);
        try {
            task.run();
        } catch (RaiseException e) {
            if (Layouts.BASIC_OBJECT.getLogicalClass(e.getRubyException()) == context.getCoreLibrary().getSystemExitClass()) {
                // SystemExit: send it to the main thread if it reached here
                ThreadRaisePrimitiveNode.raiseInThread(context, context.getThreadManager().getRootThread(), e.getRubyException(), currentNode);
            }
            throw e;
        } finally {
            cleanup(context, fiber);
        }
    }

    // Only used by the main thread which cannot easily wrap everything inside a try/finally.
    public static void start(RubyContext context, DynamicObject fiber) {
        assert RubyGuards.isRubyFiber(fiber);
        Layouts.FIBER.setThread(fiber, Thread.currentThread());
        context.getThreadManager().initializeCurrentThread(Layouts.FIBER.getRubyThread(fiber));
        Layouts.THREAD.getFiberManager(Layouts.FIBER.getRubyThread(fiber)).registerFiber(fiber);
        context.getSafepointManager().enterThread();
        // fully initialized
        Layouts.FIBER.getInitializedLatch(fiber).countDown();
    }

    // Only used by the main thread which cannot easily wrap everything inside a try/finally.
    public static void cleanup(RubyContext context, DynamicObject fiber) {
        assert RubyGuards.isRubyFiber(fiber);
        Layouts.FIBER.setAlive(fiber, false);
        context.getSafepointManager().leaveThread();
        Layouts.THREAD.getFiberManager(Layouts.FIBER.getRubyThread(fiber)).unregisterFiber(fiber);
        Layouts.FIBER.setThread(fiber, null);
    }

    /**
     * Send the Java thread that represents this fiber to sleep until it receives a resume or exit message.
     */
    private static Object[] waitForResume(RubyContext context, final DynamicObject fiber) {
        assert RubyGuards.isRubyFiber(fiber);

        final FiberMessage message = context.getThreadManager().runUntilResult(null, new ThreadManager.BlockingAction<FiberMessage>() {
            @Override
            public FiberMessage block() throws InterruptedException {
                return Layouts.FIBER.getMessageQueue(fiber).take();
            }
        });

        Layouts.THREAD.getFiberManager(Layouts.FIBER.getRubyThread(fiber)).setCurrentFiber(fiber);

        if (message instanceof FiberExitMessage) {
            throw new FiberExitException();
        } else if (message instanceof FiberExceptionMessage) {
            throw new RaiseException(((FiberExceptionMessage) message).getException());
        } else if (message instanceof FiberResumeMessage) {
            final FiberResumeMessage resumeMessage = (FiberResumeMessage) message;
            assert context.getThreadManager().getCurrentThread() == Layouts.FIBER.getRubyThread(resumeMessage.getSendingFiber());
            if (!(resumeMessage.isYield())) {
                Layouts.FIBER.setLastResumedByFiber(fiber, resumeMessage.getSendingFiber());
            }
            return resumeMessage.getArgs();
        } else {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Send a resume message to a fiber by posting into its message queue. Doesn't explicitly notify the Java
     * thread (although the queue implementation may) and doesn't wait for the message to be received.
     */
    private static void resume(DynamicObject fromFiber, DynamicObject fiber, boolean yield, Object... args) {
        assert RubyGuards.isRubyFiber(fromFiber);
        assert RubyGuards.isRubyFiber(fiber);

        Layouts.FIBER.getMessageQueue(fiber).add(new FiberResumeMessage(yield, fromFiber, args));
    }

    public static Object[] transferControlTo(RubyContext context, DynamicObject fromFiber, DynamicObject fiber, boolean yield, Object[] args) {
        assert RubyGuards.isRubyFiber(fromFiber);
        assert RubyGuards.isRubyFiber(fiber);

        resume(fromFiber, fiber, yield, args);

        return waitForResume(context, fromFiber);
    }

    public static void shutdown(DynamicObject fiber) {
        assert RubyGuards.isRubyFiber(fiber);
        assert !Layouts.FIBER.getRootFiber(fiber);
        Layouts.FIBER.getMessageQueue(fiber).add(new FiberExitMessage());
    }

    public interface FiberMessage {
    }

    public abstract static class FiberTransferNode extends CoreMethodArrayArgumentsNode {

        @Child SingleValueCastNode singleValueCastNode;

        public FiberTransferNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        protected Object singleValue(VirtualFrame frame, Object[] args) {
            if (singleValueCastNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                singleValueCastNode = insert(SingleValueCastNodeGen.create(getContext(), getSourceSection(), null));
            }
            return singleValueCastNode.executeSingleValue(frame, args);
        }

        public abstract Object executeTransferControlTo(VirtualFrame frame, DynamicObject fiber, boolean isYield, Object[] args);

        @Specialization(guards = "isRubyFiber(fiber)")
        protected Object transfer(VirtualFrame frame, DynamicObject fiber, boolean isYield, Object[] args) {
            CompilerDirectives.transferToInterpreter();

            if (!Layouts.FIBER.getAlive(fiber)) {
                throw new RaiseException(getContext().getCoreLibrary().deadFiberCalledError(this));
            }

            DynamicObject currentThread = getContext().getThreadManager().getCurrentThread();
            if (Layouts.FIBER.getRubyThread(fiber) != currentThread) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().fiberError("fiber called across threads", this));
            }

            final DynamicObject sendingFiber = Layouts.THREAD.getFiberManager(currentThread).getCurrentFiber();

            return singleValue(frame, transferControlTo(getContext(), sendingFiber, fiber, isYield, args));
        }

    }

    @CoreMethod(names = "initialize", needsBlock = true, unsupportedOperationBehavior = UnsupportedOperationBehavior.ARGUMENT_ERROR)
    public abstract static class InitializeNode extends CoreMethodArrayArgumentsNode {

        public InitializeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject initialize(DynamicObject fiber, DynamicObject block) {
            FiberNodes.initialize(getContext(), fiber, block, this);
            return nil();
        }

    }

    @CoreMethod(names = "resume", rest = true)
    public abstract static class ResumeNode extends CoreMethodArrayArgumentsNode {

        @Child FiberTransferNode fiberTransferNode;

        public ResumeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            fiberTransferNode = FiberTransferNodeFactory.create(context, sourceSection, new RubyNode[] { null, null, null });
        }

        @Specialization
        public Object resume(VirtualFrame frame, DynamicObject fiberBeingResumed, Object[] args) {
            return fiberTransferNode.executeTransferControlTo(frame, fiberBeingResumed, false, args);
        }

    }

    @CoreMethod(names = "yield", onSingleton = true, rest = true)
    public abstract static class YieldNode extends CoreMethodArrayArgumentsNode {

        @Child FiberTransferNode fiberTransferNode;

        public YieldNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            fiberTransferNode = FiberTransferNodeFactory.create(context, sourceSection, new RubyNode[] { null, null, null });
        }

        @Specialization
        public Object yield(VirtualFrame frame, Object[] args) {
            final DynamicObject currentThread = getContext().getThreadManager().getCurrentThread();
            final DynamicObject yieldingFiber = Layouts.THREAD.getFiberManager(currentThread).getCurrentFiber();
            final DynamicObject fiberYieldedTo = Layouts.FIBER.getLastResumedByFiber(yieldingFiber);

            if (Layouts.FIBER.getRootFiber(yieldingFiber) || fiberYieldedTo == null) {
                throw new RaiseException(getContext().getCoreLibrary().yieldFromRootFiberError(this));
            }

            return fiberTransferNode.executeTransferControlTo(frame, fiberYieldedTo, true, args);
        }

    }

    public static class FiberResumeMessage implements FiberMessage {

        private final boolean yield;
        private final DynamicObject sendingFiber;
        private final Object[] args;

        public FiberResumeMessage(boolean yield, DynamicObject sendingFiber, Object[] args) {
            assert RubyGuards.isRubyFiber(sendingFiber);
            this.yield = yield;
            this.sendingFiber = sendingFiber;
            this.args = args;
        }

        public boolean isYield() {
            return yield;
        }

        public DynamicObject getSendingFiber() {
            return sendingFiber;
        }

        public Object[] getArgs() {
            return args;
        }

    }

    public static class FiberExitMessage implements FiberMessage {
    }

    public static class FiberExceptionMessage implements FiberMessage {

        private final DynamicObject exception;

        public FiberExceptionMessage(DynamicObject exception) {
            this.exception = exception;
        }

        public DynamicObject getException() {
            return exception;
        }

    }

    public static class FiberExitException extends ControlFlowException {
        private static final long serialVersionUID = 1522270454305076317L;
    }

    @CoreMethod(names = "alive?")
    public abstract static class AliveNode extends UnaryCoreMethodNode {

        public AliveNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public boolean alive(DynamicObject fiber) {
            return Layouts.FIBER.getAlive(fiber);
        }

    }

    @CoreMethod(names = "current", onSingleton = true)
    public abstract static class CurrentNode extends CoreMethodNode {

        public CurrentNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public DynamicObject current() {
            final DynamicObject currentThread = getContext().getThreadManager().getCurrentThread();
            return Layouts.THREAD.getFiberManager(currentThread).getCurrentFiber();
        }

    }

    @CoreMethod(names = "allocate", constructor = true)
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        public AllocateNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            DynamicObject parent = getContext().getThreadManager().getCurrentThread();
            return createFiber(parent, rubyClass, null);
        }

    }

}
