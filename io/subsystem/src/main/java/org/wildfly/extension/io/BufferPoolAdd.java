package org.wildfly.extension.io;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;

class BufferPoolAdd extends AbstractAddStepHandler {

    static final BufferPoolAdd INSTANCE = new BufferPoolAdd();

    BufferPoolAdd() {
        super(BufferPoolResourceDefinition.ATTRIBUTES);
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model)
            throws OperationFailedException {
        final String name = context.getCurrentAddressValue();
        final ModelNode bufferSizeModel = BufferPoolResourceDefinition.BUFFER_SIZE.resolveModelAttribute(context, model);
        final ModelNode bufferPerSliceModel = BufferPoolResourceDefinition.BUFFER_PER_SLICE.resolveModelAttribute(context,
                model);
        final ModelNode directModel = BufferPoolResourceDefinition.DIRECT_BUFFERS.resolveModelAttribute(context, model);

        final int bufferSize = bufferSizeModel.isDefined() ? bufferSizeModel.asInt()
                : BufferPoolResourceDefinition.DEFAULT_BUFFER_SIZE.asInt();
        final int bufferPerSlice = bufferPerSliceModel.isDefined() ? bufferPerSliceModel.asInt()
                : BufferPoolResourceDefinition.DEFAULT_BUFFER_SIZE.asInt();
        final boolean direct = directModel.isDefined() ? directModel.asBoolean()
                : BufferPoolResourceDefinition.DEFAULT_DIRECT_BUFFERS.asBoolean();

        final BufferPoolService service = new BufferPoolService(bufferSize, bufferPerSlice, direct);
        context.getServiceTarget().addService(IOServices.BUFFER_POOL.append(name), service)
                .setInitialMode(ServiceController.Mode.ACTIVE).install();
    }
}
