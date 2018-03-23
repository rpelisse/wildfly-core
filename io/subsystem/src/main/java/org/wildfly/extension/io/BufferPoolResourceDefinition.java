/*
 *
 *  JBoss, Home of Professional Open Source.
 *  Copyright 2013, Red Hat, Inc., and individual contributors
 *  as indicated by the @author tags. See the copyright.txt file in the
 *  distribution for a full listing of individual contributors.
 *
 *  This is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as
 *  published by the Free Software Foundation; either version 2.1 of
 *  the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this software; if not, write to the Free
 *  Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 *  02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.extension.io;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.XnioByteBufferPool;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ModelOnlyWriteAttributeHandler;
import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PersistentResourceDefinition;
import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.operations.validation.IntRangeValidator;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.xnio.Pool;

/**
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a> (c) 2013 Red Hat Inc.
 */
class BufferPoolResourceDefinition extends PersistentResourceDefinition {

    static final RuntimeCapability<Void> IO_POOL_RUNTIME_CAPABILITY = RuntimeCapability.Builder.of(
            IOServices.BUFFER_POOL_CAPABILITY_NAME, true, Pool.class).build();
    static final RuntimeCapability<Void> IO_BYTE_BUFFER_POOL_RUNTIME_CAPABILITY = RuntimeCapability.Builder.of(
            IOServices.BYTE_BUFFER_POOL_CAPABILITY_NAME, true, ByteBufferPool.class).build();

    static final ModelNode DEFAULT_BUFFER_SIZE;
    static final ModelNode DEFAULT_BUFFERS_PER_REGION;
    static final ModelNode DEFAULT_DIRECT_BUFFERS;

    static {
        int defaultBufferSize;
        int defaultBuffersPerRegion;
        boolean defaultDirectBuffers;

        long maxMemory = Runtime.getRuntime().maxMemory();
        // smaller than 64mb of ram we use 512b buffers
        if (maxMemory < 64 * 1024 * 1024) {
            // use 512b buffers
            defaultDirectBuffers = false;
            defaultBufferSize = 512;
            defaultBuffersPerRegion = 10;
        } else if (maxMemory < 128 * 1024 * 1024) {
            // use 1k buffers
            defaultDirectBuffers = true;
            defaultBufferSize = 1024;
            defaultBuffersPerRegion = 10;
        } else {
            // use 16k buffers for best performance
            // as 16k is generally the max amount of data that can be sent in a single write() call
            defaultDirectBuffers = true;
            defaultBufferSize = 1024 * 16;
            defaultBuffersPerRegion = 20;
        }
        DEFAULT_BUFFER_SIZE = new ModelNode(defaultBufferSize);
        DEFAULT_BUFFERS_PER_REGION = new ModelNode(defaultBuffersPerRegion);
        DEFAULT_DIRECT_BUFFERS = new ModelNode(defaultDirectBuffers);
    }

    static final SimpleAttributeDefinition BUFFER_SIZE = new SimpleAttributeDefinitionBuilder(Constants.BUFFER_SIZE,
            ModelType.INT, true).setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES).setAllowExpression(true)
            .setValidator(new IntRangeValidator(1, true, true)).build();
    static final SimpleAttributeDefinition BUFFER_PER_SLICE = new SimpleAttributeDefinitionBuilder(Constants.BUFFER_PER_SLICE,
            ModelType.INT, true).setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES).setAllowExpression(true)
            .setValidator(new IntRangeValidator(1, true, true)).build();
    static final SimpleAttributeDefinition DIRECT_BUFFERS = new SimpleAttributeDefinitionBuilder(Constants.DIRECT_BUFFERS,
            ModelType.BOOLEAN, true).setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES).setAllowExpression(true).build();

    /* <buffer-pool name="default" buffer-size="1024" buffers-per-slice="1024"/> */
    static List<SimpleAttributeDefinition> ATTRIBUTES = Arrays.asList(BUFFER_SIZE, BUFFER_PER_SLICE, DIRECT_BUFFERS);

    public static final BufferPoolResourceDefinition INSTANCE = new BufferPoolResourceDefinition();

    private BufferPoolResourceDefinition() {
        super(new Parameters(IOExtension.BUFFER_POOL_PATH, IOExtension.getResolver(Constants.BUFFER_POOL))
                .setAddHandler(new BufferPoolAdd()).setRemoveHandler(new ReloadRequiredRemoveStepHandler())
                .setDeprecatedSince(ModelVersion.create(4)));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Collection<AttributeDefinition> getAttributes() {
        return (Collection) ATTRIBUTES;
    }

    @Override
    public void registerCapabilities(ManagementResourceRegistration resourceRegistration) {
        resourceRegistration.registerCapability(IO_POOL_RUNTIME_CAPABILITY);
        resourceRegistration.registerCapability(IO_BYTE_BUFFER_POOL_RUNTIME_CAPABILITY);
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        resourceRegistration.registerReadWriteAttribute(BUFFER_SIZE, new BufferReadAttributeHandler(BUFFER_SIZE,
                DEFAULT_BUFFER_SIZE), new BufferWriteAttributeHandler(BUFFER_SIZE));
        resourceRegistration.registerReadWriteAttribute(BUFFER_PER_SLICE, new BufferReadAttributeHandler(BUFFER_PER_SLICE,
                DEFAULT_BUFFERS_PER_REGION), new BufferWriteAttributeHandler(BUFFER_PER_SLICE));
        resourceRegistration.registerReadWriteAttribute(DIRECT_BUFFERS, new BufferReadAttributeHandler(DIRECT_BUFFERS,
                DEFAULT_DIRECT_BUFFERS), new BufferWriteAttributeHandler(DIRECT_BUFFERS));
    }

    private static class BufferReadAttributeHandler implements OperationStepHandler {

        final SimpleAttributeDefinition definition;
        final ModelNode defaultValue;

        public BufferReadAttributeHandler(SimpleAttributeDefinition definition, ModelNode defaultValue) {
            this.definition = definition;
            this.defaultValue = defaultValue;
        }

        private ModelNode returnDefaultIfNotDefined(ModelNode result) {
            return result.isDefined() ? result : defaultValue;
        }

        @Override
        public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
            context.getResult().set(
                    returnDefaultIfNotDefined(definition.resolveModelAttribute(context, Resource.Tools.readModel(context
                            .readResourceFromRoot(PathAddress.pathAddress(operation.get(OP_ADDR)))))));
        }
    }

    private static class BufferWriteAttributeHandler extends ModelOnlyWriteAttributeHandler {
        public BufferWriteAttributeHandler(SimpleAttributeDefinition definition) {
            super(definition);
        }

        @Override
        public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
            super.execute(context, operation);
            context.reloadRequired();
        }
    }

    private static final class ByteBufferPoolService implements Service<ByteBufferPool> {

        final InjectedValue<Pool> bufferPool = new InjectedValue<>();
        private volatile ByteBufferPool byteBufferPool;

        @Override
        public void start(StartContext startContext) throws StartException {
            byteBufferPool = new XnioByteBufferPool(bufferPool.getValue());
        }

        @Override
        public void stop(StopContext stopContext) {
            byteBufferPool.close();
            byteBufferPool = null;
        }

        @Override
        public ByteBufferPool getValue() throws IllegalStateException, IllegalArgumentException {
            return byteBufferPool;
        }
    }
}