/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.logging;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DISABLE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLE;
import static org.jboss.as.logging.CommonAttributes.ENABLED;
import static org.jboss.as.logging.CommonAttributes.ENCODING;
import static org.jboss.as.logging.CommonAttributes.FILTER;
import static org.jboss.as.logging.CommonAttributes.FILTER_SPEC;
import static org.jboss.as.logging.CommonAttributes.LEVEL;
import static org.jboss.as.logging.CommonAttributes.NAME;

import java.util.logging.Handler;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.DefaultAttributeMarshaller;
import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ReadResourceNameOperationStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.descriptions.ResourceDescriptionResolver;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.transform.description.ResourceTransformationDescriptionBuilder;
import org.jboss.as.logging.logmanager.PropertySorter;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a>
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
abstract class AbstractHandlerDefinition extends TransformerResourceDefinition {

    public static final String UPDATE_OPERATION_NAME = "update-properties";
    public static final String CHANGE_LEVEL_OPERATION_NAME = "change-log-level";

    public static final PropertyAttributeDefinition FORMATTER = PropertyAttributeDefinition.Builder.of("formatter", ModelType.STRING, true)
            .setAllowExpression(true)
            .setAlternatives("named-formatter")
            .setAttributeMarshaller(new DefaultAttributeMarshaller() {
                @Override
                public void marshallAsElement(final AttributeDefinition attribute, final ModelNode resourceModel, final boolean marshallDefault, final XMLStreamWriter writer) throws XMLStreamException {
                    if (isMarshallable(attribute, resourceModel, marshallDefault)) {
                        writer.writeStartElement(attribute.getXmlName());
                        writer.writeStartElement(PatternFormatterResourceDefinition.PATTERN_FORMATTER.getXmlName());
                        final String pattern = resourceModel.get(attribute.getName()).asString();
                        writer.writeAttribute(PatternFormatterResourceDefinition.PATTERN.getXmlName(), pattern);
                        writer.writeEndElement();
                        writer.writeEndElement();
                    }
                }
            })
            .setDefaultValue(new ModelNode("%d{HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n"))
            .build();

    public static final SimpleAttributeDefinition NAMED_FORMATTER = SimpleAttributeDefinitionBuilder.create("named-formatter", ModelType.STRING, true)
            .setAllowExpression(false)
            .setAlternatives("formatter")
            .setAttributeMarshaller(new DefaultAttributeMarshaller() {
                @Override
                public void marshallAsElement(final AttributeDefinition attribute, final ModelNode resourceModel, final boolean marshallDefault, final XMLStreamWriter writer) throws XMLStreamException {
                    if (isMarshallable(attribute, resourceModel, marshallDefault)) {
                        writer.writeStartElement(FORMATTER.getXmlName());
                        writer.writeStartElement(attribute.getXmlName());
                        String content = resourceModel.get(attribute.getName()).asString();
                        writer.writeAttribute(CommonAttributes.NAME.getName(), content);
                        writer.writeEndElement();
                        writer.writeEndElement();
                    }
                }
            })
            .build();

    static final AttributeDefinition[] DEFAULT_ATTRIBUTES = {
            LEVEL,
            ENABLED,
            ENCODING,
            FORMATTER,
            FILTER_SPEC,
    };

    static final AttributeDefinition[] LEGACY_ATTRIBUTES = {
            FILTER,
    };

    private final OperationStepHandler writeHandler;
    private final AttributeDefinition[] writableAttributes;
    private final AttributeDefinition[] readOnlyAttributes;
    private final PropertySorter propertySorter;
    private final boolean registerLegacyOps;

    protected AbstractHandlerDefinition(final PathElement path,
                                        final Class<? extends Handler> type,
                                        final AttributeDefinition[] attributes) {
        this(path, type, PropertySorter.NO_OP, attributes);
    }

    protected AbstractHandlerDefinition(final PathElement path,
                                        final Class<? extends Handler> type,
                                        final PropertySorter propertySorter,
                                        final AttributeDefinition[] attributes) {
        this(path, type, propertySorter, attributes, null, attributes);
    }

    protected AbstractHandlerDefinition(final PathElement path,
                                        final boolean registerLegacyOps,
                                        final Class<? extends Handler> type,
                                        final PropertySorter propertySorter,
                                        final AttributeDefinition[] attributes) {
        this(path, registerLegacyOps, type, propertySorter, attributes, null, attributes);
    }

    protected AbstractHandlerDefinition(final PathElement path,
                                        final Class<? extends Handler> type,
                                        final AttributeDefinition[] attributes,
                                        final ConfigurationProperty<?>... constructionProperties) {
        this(path, type, PropertySorter.NO_OP, attributes, null, attributes, constructionProperties);
    }

    protected AbstractHandlerDefinition(final PathElement path,
                                        final Class<? extends Handler> type,
                                        final PropertySorter propertySorter,
                                        final AttributeDefinition[] addAttributes,
                                        final AttributeDefinition[] readOnlyAttributes,
                                        final AttributeDefinition[] writableAttributes,
                                        final ConfigurationProperty<?>... constructionProperties) {
        this(path, true, type, propertySorter, addAttributes, readOnlyAttributes, writableAttributes, constructionProperties);
    }

    protected AbstractHandlerDefinition(final PathElement path,
                                        final boolean registerLegacyOps,
                                        final Class<? extends Handler> type,
                                        final PropertySorter propertySorter,
                                        final AttributeDefinition[] addAttributes,
                                        final AttributeDefinition[] readOnlyAttributes,
                                        final AttributeDefinition[] writableAttributes,
                                        final ConfigurationProperty<?>... constructionProperties) {
        super(path,
                LoggingExtension.getResourceDescriptionResolver(path.getKey()),
                new HandlerOperations.HandlerAddOperationStepHandler(propertySorter, type, addAttributes, constructionProperties),
                HandlerOperations.REMOVE_HANDLER);
        this.registerLegacyOps = registerLegacyOps;
        this.writableAttributes = writableAttributes;
        writeHandler = new HandlerOperations.LogHandlerWriteAttributeHandler(propertySorter, this.writableAttributes);
        this.readOnlyAttributes = readOnlyAttributes;
        this.propertySorter = propertySorter;
    }

    @Override
    public void registerAttributes(final ManagementResourceRegistration resourceRegistration) {
        for (AttributeDefinition def : writableAttributes) {
            // Filter requires a special reader
            if (def.getName().equals(FILTER.getName())) {
                resourceRegistration.registerReadWriteAttribute(def, LoggingOperations.ReadFilterOperationStepHandler.INSTANCE, writeHandler);
            } else {
                resourceRegistration.registerReadWriteAttribute(def, null, writeHandler);
            }
        }
        if (readOnlyAttributes != null) {
            for (AttributeDefinition def : readOnlyAttributes) {
                resourceRegistration.registerReadOnlyAttribute(def, null);
            }
        }
        // Be careful with this attribute. It needs to show up in the "add" operation param list so ops from legacy
        // scripts will validate. It does because it's registered as an attribute but is not setResourceOnly(true)
        // so DefaultResourceAddDescriptionProvider adds it to the param list
        resourceRegistration.registerReadOnlyAttribute(NAME, ReadResourceNameOperationStepHandler.INSTANCE);
    }

    @Override
    public void registerOperations(final ManagementResourceRegistration registration) {
        super.registerOperations(registration);

        if (registerLegacyOps) {
            final ResourceDescriptionResolver resourceDescriptionResolver = getResourceDescriptionResolver();
            registration.registerOperationHandler(new SimpleOperationDefinitionBuilder(ENABLE, resourceDescriptionResolver)
                    .setDeprecated(ModelVersion.create(1, 2, 0))
                    .build(), HandlerOperations.ENABLE_HANDLER);

            registration.registerOperationHandler(new SimpleOperationDefinitionBuilder(DISABLE, resourceDescriptionResolver)
                    .setDeprecated(ModelVersion.create(1, 2, 0))
                    .build(), HandlerOperations.DISABLE_HANDLER);

            registration.registerOperationHandler(new SimpleOperationDefinitionBuilder(CHANGE_LEVEL_OPERATION_NAME, resourceDescriptionResolver)
                    .setDeprecated(ModelVersion.create(1, 2, 0))
                    .setParameters(CommonAttributes.LEVEL)
                    .build(), HandlerOperations.CHANGE_LEVEL);

            final SimpleOperationDefinition updateProperties = new SimpleOperationDefinitionBuilder(UPDATE_OPERATION_NAME, resourceDescriptionResolver)
                    .setDeprecated(ModelVersion.create(1, 2, 0))
                    .setParameters(writableAttributes)
                    .build();

            registration.registerOperationHandler(updateProperties, new HandlerOperations.HandlerUpdateOperationStepHandler(propertySorter, writableAttributes));
        }
    }

    @Override
    public void registerTransformers(final KnownModelVersion modelVersion,
                                     final ResourceTransformationDescriptionBuilder rootResourceBuilder,
                                     final ResourceTransformationDescriptionBuilder loggingProfileBuilder) {
        if (modelVersion.hasTransformers()) {
            final PathElement pathElement = getPathElement();
            final ResourceTransformationDescriptionBuilder resourceBuilder = rootResourceBuilder.addChildResource(pathElement);
            final ResourceTransformationDescriptionBuilder loggingProfileResourceBuilder = loggingProfileBuilder.addChildResource(pathElement);
            registerResourceTransformers(modelVersion, resourceBuilder, loggingProfileResourceBuilder);
        }
    }

    /**
     * Register the transformers for the resource.
     *
     * @param modelVersion          the model version we're registering
     * @param resourceBuilder       the builder for the resource
     * @param loggingProfileBuilder the builder for the logging profile
     */
    protected void registerResourceTransformers(final KnownModelVersion modelVersion,
                                                         final ResourceTransformationDescriptionBuilder resourceBuilder,
                                                         final ResourceTransformationDescriptionBuilder loggingProfileBuilder) {
        // do nothing by default
    }
}
