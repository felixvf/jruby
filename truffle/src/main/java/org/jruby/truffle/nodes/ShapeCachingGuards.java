/*
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */

package org.jruby.truffle.nodes;

import org.jruby.truffle.runtime.layouts.Layouts;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Shape;

public abstract class ShapeCachingGuards {

    public static boolean updateShape(DynamicObject object) {
        CompilerDirectives.transferToInterpreter();
        return object.updateShape();
    }

    public static boolean isArrayShape(Shape shape) {
        return Layouts.ARRAY.isArray(shape.getObjectType());
    }

    public static boolean isQueueShape(Shape shape) {
        return Layouts.QUEUE.isQueue(shape.getObjectType());
    }

    public static boolean isBasicObjectShape(Shape shape) {
        return shape.getObjectType().getClass().getName().endsWith(".BasicObjectType"); // FIXME
    }

}