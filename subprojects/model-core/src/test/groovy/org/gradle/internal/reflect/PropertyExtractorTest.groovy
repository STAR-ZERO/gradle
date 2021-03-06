/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.reflect

import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.ImmutableSet
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.tasks.properties.DefaultParameterValidationContext
import spock.lang.Issue
import spock.lang.Specification

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

class PropertyExtractorTest extends Specification {

    def extractor = new PropertyExtractor("a thing annotation",
        ImmutableSet.of(PropertyType1, PropertyType2, PropertyType1Override),
        ImmutableSet.of(PropertyType1, PropertyType2, PropertyType1Override, SupportingAnnotation),
        ImmutableMultimap.of(PropertyType1, PropertyType1Override),
        ImmutableSet.of(KnownAnnotation),
        ImmutableSet.of(Object, GroovyObject),
        ImmutableSet.of(Object, GroovyObject))

    class WithPropertyType1 {
        @PropertyType1 getFile() {}
    }

    class WithPropertyType2 extends WithPropertyType1 {
        @PropertyType2 @Override getFile() {}
    }

    class WithPropertyOverride extends WithPropertyType2 {
        @PropertyType1Override @Override getFile() {}
    }

    def "can override property type in subclasses"() {
        expect:
        extractor.extractPropertyMetadata(WithPropertyType1).left*.propertyType == [PropertyType1]
        extractor.extractPropertyMetadata(WithPropertyType2).left*.propertyType == [PropertyType2]
        extractor.extractPropertyMetadata(WithPropertyOverride).left*.propertyType == [PropertyType1Override]
    }

    class OverridingProperties {
        @PropertyType1 @PropertyType1Override FileCollection inputFiles1
        @PropertyType1Override @PropertyType1 FileCollection inputFiles2
    }

    def "overriding annotation on same property takes effect"() {
        when:
        def result = extractor.extractPropertyMetadata(OverridingProperties)

        then:
        assertPropertyTypes(result.left, inputFiles1: PropertyType1Override, inputFiles2: PropertyType1Override)
        result.right.empty
    }

    class BasePropertyType1OverrideProperty {
        @PropertyType1Override FileCollection overriddenType1Override
        @PropertyType1 FileCollection overriddenType1
    }

    class OverridingPropertyType1Property extends BasePropertyType1OverrideProperty {
        @PropertyType1
        @Override
        FileCollection getOverriddenType1Override() {
            return super.getOverriddenType1Override()
        }

        @PropertyType1Override
        @Override
        FileCollection getOverriddenType1() {
            return super.getOverriddenType1()
        }
    }

    @Issue("https://github.com/gradle/gradle/issues/913")
    def "overriding annotation does not take precedence in sub-type"() {
        when:
        def result = extractor.extractPropertyMetadata(OverridingPropertyType1Property)

        then:
        assertPropertyTypes(result.left, overriddenType1Override: PropertyType1, overriddenType1: PropertyType1Override)
        result.right.empty
    }

    class WithBothFieldAndGetterAnnotation {
        @PropertyType1 FileCollection inputFiles

        @PropertyType1
        FileCollection getInputFiles() {
            return inputFiles
        }
    }

    def "warns about both method and field having the same annotation"() {
        when:
        def result = extractor.extractPropertyMetadata(WithBothFieldAndGetterAnnotation)

        then:
        assertPropertyTypes(result.left, inputFiles: PropertyType1)
        collectProblems(result.right) == ["Property 'inputFiles' has both a getter and field declared with annotation @${PropertyType1.simpleName}."]
    }

    class WithBothFieldAndGetterAnnotationButIrrelevant {
        @IrrelevantAnnotation FileCollection inputFiles

        @IrrelevantAnnotation @PropertyType1
        FileCollection getInputFiles() {
            return inputFiles
        }
    }

    def "doesn't warn about both method and field having the same irrelevant annotation"() {
        when:
        def result = extractor.extractPropertyMetadata(WithBothFieldAndGetterAnnotationButIrrelevant)

        then:
        assertPropertyTypes(result.left, inputFiles: PropertyType1)
        result.right.empty
    }

    class WithAnnotationsOnPrivateProperties {
        @PropertyType1
        private String getInput() {
            'Input'
        }

        @PropertyType2
        private File getOutputFile() {
            null
        }

        private String getNotAnInput() {
            'Not an input'
        }
    }

    def "warns about annotations on private properties"() {
        when:
        def result = extractor.extractPropertyMetadata(WithAnnotationsOnPrivateProperties)

        then:
        assertPropertyTypes(result.left, input: PropertyType1, outputFile: PropertyType2)
        collectProblems(result.right) == [
            "Property 'input' is private and annotated with @${PropertyType1.simpleName}.",
            "Property 'outputFile' is private and annotated with @${PropertyType2.simpleName}."
        ]
    }

    class WithUnannotatedProperties {
        private String getIgnored() {
            'Input'
        }

        String getBad1() {
            null
        }

        protected String getBad2() {
            null
        }
    }

    def "warns about non-private getters that are not annotated"() {
        when:
        def result = extractor.extractPropertyMetadata(WithUnannotatedProperties)

        then:
        result.left.empty
        collectProblems(result.right) == [
            "Property 'bad1' is not annotated with a thing annotation.",
            "Property 'bad2' is not annotated with a thing annotation."
        ]
    }

    class WithConflictingPropertyTypes {
        @PropertyType1
        @PropertyType2
        File inputThing

        @PropertyType2
        @PropertyType1
        File confusedFile
    }

    def "warns about conflicting property types being specified"() {
        when:
        def result = extractor.extractPropertyMetadata(WithConflictingPropertyTypes)

        then:
        assertPropertyTypes(result.left, inputThing: PropertyType1, confusedFile: PropertyType2)
        collectProblems(result.right) == [
            "Property 'confusedFile' has conflicting property types declared: @${PropertyType1.simpleName}, @${PropertyType2.simpleName}.",
            "Property 'inputThing' has conflicting property types declared: @${PropertyType1.simpleName}, @${PropertyType2.simpleName}."
        ]
    }

    class WithUnsupportedPropertyTypes {
        @KnownAnnotation
        File inputThing

        @PropertyType1
        @KnownAnnotation
        File hasBoth
    }

    def "warns about properties annotated with known bu unsupported annotations"() {
        when:
        def result = extractor.extractPropertyMetadata(WithUnsupportedPropertyTypes)

        then:
        assertPropertyTypes(result.left, hasBoth: PropertyType1)
        collectProblems(result.right) == [
            "Property 'hasBoth' is annotated with unsupported annotation @${KnownAnnotation.simpleName}.",
            "Property 'inputThing' is annotated with unsupported annotation @${KnownAnnotation.simpleName}."
        ]
    }

    class WithNonConflictingPropertyTypes {
        @PropertyType1
        @PropertyType1Override
        FileCollection classpath
    }

    def "doesn't warn about non-conflicting property types being specified"() {
        when:
        def result = extractor.extractPropertyMetadata(WithNonConflictingPropertyTypes)

        then:
        assertPropertyTypes(result.left, classpath: PropertyType1Override)
        result.right.empty
    }

    static class SimpleType {
        @PropertyType1 String inputString
        @PropertyType1Override File inputFile
        @SupportingAnnotation("inputDirectory")
        @PropertyType2 File inputDirectory
        @IrrelevantAnnotation Object injectedService
    }

    def "can get annotated properties of simple type"() {
        when:
        def result = extractor.extractPropertyMetadata(SimpleType)

        then:
        assertPropertyTypes(result.left,
            inputString: PropertyType1,
            inputFile: PropertyType1Override,
            inputDirectory: PropertyType2
        )
        collectProblems(result.right) == ["Property 'injectedService' is not annotated with a thing annotation."]
    }

    static abstract class BaseClassWithGetters {
        @PropertyType2
        abstract Iterable<String> getStrings()
    }

    static class WithGetters extends BaseClassWithGetters {
        @PropertyType1
        boolean isBoolean() {
            return true
        }

        @SupportingAnnotation("getBoolean")
        boolean getBoolean() {
            return isBoolean()
        }

        @SupportingAnnotation("getStrings")
        @Override
        List<String> getStrings() {
            return ["some", "strings"]
        }
    }

    def "annotations are gathered from different getters"() {
        when:
        def result = extractor.extractPropertyMetadata(WithGetters)

        then:
        assertPropertyTypes(result.left, boolean: PropertyType1, strings: PropertyType2)
        result.right.empty
    }

    private static class BaseType {
        @PropertyType1 String baseValue
        @PropertyType1 String superclassValue
        @PropertyType1 String superclassValueWithDuplicateAnnotation
        String nonAnnotatedBaseValue
    }

    private static class OverridingType extends BaseType {
        @Override
        String getSuperclassValue() {
            return super.getSuperclassValue()
        }

        @PropertyType1 @Override
        String getSuperclassValueWithDuplicateAnnotation() {
            return super.getSuperclassValueWithDuplicateAnnotation()
        }

        @PropertyType1 @Override
        String getNonAnnotatedBaseValue() {
            return super.getNonAnnotatedBaseValue()
        }
    }

    def "overridden properties inherit super-class annotations"() {
        when:
        def result = extractor.extractPropertyMetadata(OverridingType)

        then:
        assertPropertyTypes(result.left,
            baseValue: PropertyType1,
            nonAnnotatedBaseValue: PropertyType1,
            superclassValue: PropertyType1,
            superclassValueWithDuplicateAnnotation: PropertyType1,
        )
        result.right.empty
    }

    private interface TaskSpec {
        @PropertyType1
        String getInterfaceValue()
    }

    private static class InterfaceImplementingType implements TaskSpec {
        @Override
        String getInterfaceValue() {
            "value"
        }
    }

    def "implemented properties inherit interface annotations"() {
        when:
        def result = extractor.extractPropertyMetadata(InterfaceImplementingType)

        then:
        assertPropertyTypes(result.left,
            interfaceValue: PropertyType1
        )
        result.right.empty
    }

    @SuppressWarnings("GroovyUnusedDeclaration")
    private static class IsGetterType {
        @PropertyType1
        private boolean feature1
        private boolean feature2

        boolean isFeature1() {
            return feature1
        }
        void setFeature1(boolean enabled) {
            this.feature1 = enabled
        }
        boolean isFeature2() {
            return feature2
        }
        void setFeature2(boolean enabled) {
            this.feature2 = enabled
        }
    }

    @Issue("https://issues.gradle.org/browse/GRADLE-2115")
    def "annotation on private field is recognized for is-getter"() {
        when:
        def result = extractor.extractPropertyMetadata(IsGetterType)

        then:
        assertPropertyTypes(result.left,
            feature1: PropertyType1
        )
        collectProblems(result.right) == ["Property 'feature2' is not annotated with a thing annotation."]
    }

    private static List<String> collectProblems(Collection<ValidationProblem> problems) {
        def result = []
        def context = new DefaultParameterValidationContext(result)
        problems.forEach {
            it.collect(null, context)
        }
        return result
    }

    private static void assertPropertyTypes(Map<String, ?> expectedPropertyTypes, Set<PropertyMetadata> typeMetadata) {
        def propertyTypes = typeMetadata.collectEntries { propertyMetadata ->
            [(propertyMetadata.propertyName): propertyMetadata.propertyType]
        }
        assert propertyTypes == expectedPropertyTypes
    }
}

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.FIELD])
@interface PropertyType1 {
}

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.FIELD])
@interface PropertyType2 {
}

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.FIELD])
@interface PropertyType1Override {
}

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.FIELD])
@interface SupportingAnnotation {
    String value()
}

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.FIELD])
@interface KnownAnnotation {
}

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.FIELD])
@interface IrrelevantAnnotation {
}
