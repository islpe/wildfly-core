/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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

package org.jboss.as.controller.descriptions;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ATTRIBUTES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILDREN;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DYNAMIC;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MAX_OCCURS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MIN_OCCURS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MODEL_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NOTIFICATIONS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATIONS;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.DeprecationData;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.access.management.AccessConstraintDescriptionProviderUtil;
import org.jboss.as.controller.capability.Capability;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;

/**
 * Provides a default description of a resource by analyzing the registry metadata.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class DefaultResourceDescriptionProvider implements DescriptionProvider {

    private final ImmutableManagementResourceRegistration registration;
    private final ResourceDescriptionResolver descriptionResolver;
    private final DeprecationData deprecationData;

    public DefaultResourceDescriptionProvider(final ImmutableManagementResourceRegistration registration,
                                              final ResourceDescriptionResolver descriptionResolver) {
        this(registration, descriptionResolver, null);
    }

    public DefaultResourceDescriptionProvider(final ImmutableManagementResourceRegistration registration,
                                              final ResourceDescriptionResolver descriptionResolver,
                                              final DeprecationData deprecationData) {
        this.registration = registration;
        this.descriptionResolver = descriptionResolver;
        this.deprecationData = deprecationData;
    }

    @Override
    public ModelNode getModelDescription(Locale locale) {
        ModelNode result = new ModelNode();

        final ResourceBundle bundle = descriptionResolver.getResourceBundle(locale);
        result.get(DESCRIPTION).set(descriptionResolver.getResourceDescription(locale, bundle));

        // Output min and max occurs if they are non-default values
        int minOccurs = registration.getMinOccurs();
        if (minOccurs > 0) {
            result.get(MIN_OCCURS).set(minOccurs);
        }
        int maxOccurs = registration.getMaxOccurs();
        PathAddress pa = registration.getPathAddress();
        if (pa == null || pa.size() == 0) {
            // Root node has no documented 'default'
            result.get(MAX_OCCURS).set(maxOccurs);
        } else {
            int defaultMax = pa.getLastElement().isWildcard() ? Integer.MAX_VALUE : 1;
            if (maxOccurs != defaultMax) {
                result.get(MAX_OCCURS).set(maxOccurs);
            }
        }

        Set<? extends Capability> capabilities = registration.getCapabilities();
        if (capabilities!=null&&!capabilities.isEmpty()){
            for (Capability capability: capabilities) {
                ModelNode cap = result.get(ModelDescriptionConstants.CAPABILITIES).add();
                cap.get(NAME).set(capability.getName());
                cap.get(DYNAMIC).set(capability.isDynamicallyNamed());
            }
        }

        if (deprecationData != null) {
            ModelNode deprecated = addDeprecatedInfo(result);
            deprecated.get(ModelDescriptionConstants.REASON).set(descriptionResolver.getResourceDeprecatedDescription(locale, bundle));
        }
        if (registration.isRuntimeOnly()){
            result.get(ModelDescriptionConstants.STORAGE).set(ModelDescriptionConstants.RUNTIME_ONLY);
        }
        AccessConstraintDescriptionProviderUtil.addAccessConstraints(result, registration.getAccessConstraints(), locale);

        // Sort the attribute descriptions based on attribute group and then attribute name
        Set<String> attributeNames = registration.getAttributeNames(PathAddress.EMPTY_ADDRESS);

        Map<AttributeDefinition.NameAndGroup, ModelNode> sortedDescriptions = new TreeMap<>();
        for (String attr : attributeNames)  {
            AttributeAccess attributeAccess = registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attr);
            AttributeDefinition def = attributeAccess.getAttributeDefinition();
            if (def != null) {
                ModelNode attrDesc = new ModelNode();
                // def will add the description to attrDesc under "attributes" => { attr
                def.addResourceAttributeDescription(attrDesc, descriptionResolver, locale, bundle);
                sortedDescriptions.put(new AttributeDefinition.NameAndGroup(def), attrDesc.get(ATTRIBUTES, attr));
            } else {
                // Just store a placeholder
                sortedDescriptions.put(new AttributeDefinition.NameAndGroup(attr), new ModelNode());
            }
        }

        // Store the sorted descriptions into the overall result
        final ModelNode attributes = result.get(ATTRIBUTES).setEmptyObject();
        for (Map.Entry<AttributeDefinition.NameAndGroup, ModelNode> entry : sortedDescriptions.entrySet()) {
            attributes.get(entry.getKey().getName()).set(entry.getValue());
        }

        result.get(OPERATIONS); // placeholder

        result.get(NOTIFICATIONS); // placeholder

        final ModelNode children = result.get(CHILDREN).setEmptyObject();

        Set<PathElement> childAddresses = registration.getChildAddresses(PathAddress.EMPTY_ADDRESS);
        Set<String> childTypes = new HashSet<String>();
        for (PathElement childAddress : childAddresses) {
            String key = childAddress.getKey();
            if (childTypes.add(key)) {
                final ModelNode childNode = children.get(key);
                childNode.get(DESCRIPTION).set(descriptionResolver.getChildTypeDescription(key, locale, bundle));
                childNode.get(MODEL_DESCRIPTION); // placeholder
            }
        }

        return result;
    }

    private ModelNode addDeprecatedInfo(final ModelNode model) {
        ModelNode deprecated = model.get(ModelDescriptionConstants.DEPRECATED);
        deprecated.get(ModelDescriptionConstants.SINCE).set(deprecationData.getSince().toString());
        deprecated.get(ModelDescriptionConstants.REASON);
        return deprecated;
    }

}
