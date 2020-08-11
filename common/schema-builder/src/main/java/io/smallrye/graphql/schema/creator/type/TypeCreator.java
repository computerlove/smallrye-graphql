package io.smallrye.graphql.schema.creator.type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.logging.Logger;

import io.smallrye.graphql.schema.Annotations;
import io.smallrye.graphql.schema.ScanningContext;
import io.smallrye.graphql.schema.creator.FieldCreator;
import io.smallrye.graphql.schema.creator.OperationCreator;
import io.smallrye.graphql.schema.creator.ReferenceCreator;
import io.smallrye.graphql.schema.helper.DescriptionHelper;
import io.smallrye.graphql.schema.helper.Direction;
import io.smallrye.graphql.schema.helper.MethodHelper;
import io.smallrye.graphql.schema.helper.SourceOperationHelper;
import io.smallrye.graphql.schema.helper.TypeNameHelper;
import io.smallrye.graphql.schema.model.Operation;
import io.smallrye.graphql.schema.model.OperationType;
import io.smallrye.graphql.schema.model.Reference;
import io.smallrye.graphql.schema.model.ReferenceType;
import io.smallrye.graphql.schema.model.Type;

/**
 * This creates a type object.
 * 
 * The type object has fields that might reference other types that should still be created.
 * It might also implement some interfaces that should be created.
 * It might also have some operations that reference other types that should still be created.
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class TypeCreator implements Creator<Type> {
    private static final Logger LOG = Logger.getLogger(TypeCreator.class.getName());

    private final ReferenceCreator referenceCreator;
    private final FieldCreator fieldCreator;
    private final OperationCreator operationCreator;

    public TypeCreator(ReferenceCreator referenceCreator, FieldCreator fieldCreator, OperationCreator operationCreator) {
        this.referenceCreator = referenceCreator;
        this.fieldCreator = fieldCreator;
        this.operationCreator = operationCreator;
    }

    @Override
    public Type create(ClassInfo classInfo, Reference reference) {
        LOG.debug("Creating Type from " + classInfo.name().toString() + " for reference " + reference.getName());

        Annotations annotations = Annotations.getAnnotationsForClass(classInfo);

        // Name
        String name = TypeNameHelper.getAnyTypeName(ReferenceType.TYPE, classInfo, annotations,
                TypeNameHelper.createParametrizedTypeNameExtension(reference));

        // Description
        String description = DescriptionHelper.getDescriptionForType(annotations).orElse(null);

        Type type = new Type(classInfo.name().toString(), name, description);

        // Fields
        addFields(type, classInfo, reference);

        // Operations
        addOperations(type, classInfo);

        // Interfaces
        addInterfaces(type, classInfo);

        return type;
    }

    private void addFields(Type type, ClassInfo classInfo, Reference reference) {
        // Fields
        List<MethodInfo> allMethods = new ArrayList<>();
        Map<String, FieldInfo> allFields = new HashMap<>();

        // Find all methods and properties up the tree
        for (ClassInfo c = classInfo; c != null; c = ScanningContext.getIndex().getClassByName(c.superName())) {
            if (!c.toString().startsWith(JAVA_DOT)) { // Not java objects
                allMethods.addAll(c.methods());
                if (c.fields() != null && !c.fields().isEmpty()) {
                    for (final FieldInfo fieldInfo : c.fields()) {
                        allFields.putIfAbsent(fieldInfo.name(), fieldInfo);
                    }
                }
            }
        }

        for (MethodInfo methodInfo : allMethods) {
            if (MethodHelper.isPropertyMethod(Direction.OUT, methodInfo.name())) {
                String fieldName = MethodHelper.getPropertyName(Direction.OUT, methodInfo.name());
                FieldInfo fieldInfo = allFields.remove(fieldName);
                fieldCreator.createFieldForPojo(Direction.OUT, fieldInfo, methodInfo, reference)
                        .ifPresent(type::addField);
            }
        }

        // See what fields are left (this is fields without methods)
        if (!allFields.isEmpty()) {
            for (FieldInfo fieldInfo : allFields.values()) {
                fieldCreator.createFieldForPojo(Direction.OUT, fieldInfo, reference)
                        .ifPresent(type::addField);
            }
        }
    }

    private void addOperations(Type type, ClassInfo classInfo) {
        SourceOperationHelper sourceOperationHelper = new SourceOperationHelper();
        Map<DotName, List<MethodParameterInfo>> sourceFields = sourceOperationHelper.getSourceAnnotations();
        Map<DotName, List<MethodParameterInfo>> batchedFields = sourceOperationHelper.getSourceListAnnotations();
        type.setOperations(toOperations(sourceFields, type, classInfo, false));
        type.setBatchOperations(toOperations(batchedFields, type, classInfo, true));
    }

    private Map<String, Operation> toOperations(Map<DotName, List<MethodParameterInfo>> sourceFields, Type type,
            ClassInfo classInfo, boolean batched) {
        // See if there is source operations for this class
        Map<String, Operation> operations = new HashMap<>();
        if (sourceFields.containsKey(classInfo.name())) {
            List<MethodParameterInfo> methodParameterInfos = sourceFields.get(classInfo.name());
            for (MethodParameterInfo methodParameterInfo : methodParameterInfos) {
                MethodInfo methodInfo = methodParameterInfo.method();
                Operation o = operationCreator.createOperation(methodInfo, OperationType.Source, type, batched);
                operations.put(o.getName(), o);
            }
        }
        return operations;
    }

    private void addInterfaces(Type type, ClassInfo classInfo) {
        List<DotName> interfaceNames = classInfo.interfaceNames();
        for (DotName interfaceName : interfaceNames) {
            // Ignore java interfaces (like Serializable)
            if (!interfaceName.toString().startsWith(JAVA_DOT)) {
                ClassInfo interfaceInfo = ScanningContext.getIndex().getClassByName(interfaceName);
                if (interfaceInfo != null) {
                    Reference interfaceRef = referenceCreator.createReference(Direction.OUT, interfaceInfo);
                    type.addInterface(interfaceRef);
                }
            }
        }
    }

    private static final String JAVA_DOT = "java.";

}
