package io.github.nextentity.integration;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.integration.config.IntegrationTestContext;
import io.github.nextentity.integration.config.IntegrationTestProvider;
import io.github.nextentity.integration.dto.*;
import io.github.nextentity.integration.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

///
/// Embedded projection integration tests.
/// <p>
/// 上游用 `@EntityPath("address.street")` 注解把 DTO 属性指向嵌入列，`select(Dto.class)` 直接
/// 扁平化；3.x 无该注解，`select(Class)` 只认实体单层属性名（`core.builder.DtoProjection` 的
/// `column(...)` 直接 `AttributePath.of(entityType, property)`），故本轮改成
/// `project(ProjectionMapper)`：列由 `EntityPath.of(X::getAddress).get(Address::getCity)` 显式给出，
/// `map(Tuple)` 把同一组值装进同一个 DTO。断言与期望值逐行保持上游原样，只改调用形态。
/// 接口投影、嵌套 DTO、注解类型不匹配三例删除，见 `porting/NOT-PORTED-round3-port-c.md`。
@DisplayName("Embedded Projection Integration Tests")
public class EmbeddedProjectionIntegrationTest {

    private static final ProjectionMapper<PersonWithAddress, PersonAddressBasic> PERSON_ADDRESS_BASIC = mapper(
            List.of(
                    Path.of(PersonWithAddress::getId),
                    Path.of(PersonWithAddress::getName),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getStreet),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getCity),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getZipCode)),
            row -> {
                PersonAddressBasic dto = new PersonAddressBasic();
                dto.setId((Long) row.get(0));
                dto.setName((String) row.get(1));
                dto.setStreet((String) row.get(2));
                dto.setCity((String) row.get(3));
                dto.setZipCode((String) row.get(4));
                return dto;
            });

    private static final ProjectionMapper<PersonWithAddress, PersonAddressRecord> PERSON_ADDRESS_RECORD = mapper(
            List.of(
                    Path.of(PersonWithAddress::getId),
                    Path.of(PersonWithAddress::getName),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getStreet),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getCity),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getZipCode)),
            row -> new PersonAddressRecord(
                    (Long) row.get(0),
                    (String) row.get(1),
                    (String) row.get(2),
                    (String) row.get(3),
                    (String) row.get(4)));

    private static final ProjectionMapper<PersonWithOverriddenAddress, PersonAddressOverridden> PERSON_ADDRESS_OVERRIDDEN = mapper(
            List.of(
                    Path.of(PersonWithOverriddenAddress::getId),
                    Path.of(PersonWithOverriddenAddress::getName),
                    EntityPath.of(PersonWithOverriddenAddress::getAddress).get(Address::getStreet),
                    EntityPath.of(PersonWithOverriddenAddress::getAddress).get(Address::getCity),
                    EntityPath.of(PersonWithOverriddenAddress::getAddress).get(Address::getZipCode)),
            row -> {
                PersonAddressOverridden dto = new PersonAddressOverridden();
                dto.setId((Long) row.get(0));
                dto.setName((String) row.get(1));
                dto.setStreet((String) row.get(2));
                dto.setCity((String) row.get(3));
                dto.setZipCode((String) row.get(4));
                return dto;
            });

    private static final ProjectionMapper<PersonWithNestedAddress, PersonContactBasic> PERSON_CONTACT_BASIC = mapper(
            List.of(
                    Path.of(PersonWithNestedAddress::getId),
                    Path.of(PersonWithNestedAddress::getName),
                    EntityPath.of(PersonWithNestedAddress::getContactInfo).get(ContactInfo::getEmail),
                    EntityPath.of(PersonWithNestedAddress::getContactInfo).get(ContactInfo::getPhone),
                    EntityPath.of(PersonWithNestedAddress::getContactInfo).get(ContactInfo::getAddress).get(Address::getStreet),
                    EntityPath.of(PersonWithNestedAddress::getContactInfo).get(ContactInfo::getAddress).get(Address::getCity),
                    EntityPath.of(PersonWithNestedAddress::getContactInfo).get(ContactInfo::getAddress).get(Address::getZipCode)),
            row -> {
                PersonContactBasic dto = new PersonContactBasic();
                dto.setId((Long) row.get(0));
                dto.setName((String) row.get(1));
                dto.setEmail((String) row.get(2));
                dto.setPhone((String) row.get(3));
                dto.setStreet((String) row.get(4));
                dto.setCity((String) row.get(5));
                dto.setZipCode((String) row.get(6));
                return dto;
            });

    private static final ProjectionMapper<PersonWithCrossLayerEmbedded, PersonCrossLayerBasic> PERSON_CROSS_LAYER_BASIC = mapper(
            List.of(
                    Path.of(PersonWithCrossLayerEmbedded::getId),
                    Path.of(PersonWithCrossLayerEmbedded::getName),
                    EntityPath.of(PersonWithCrossLayerEmbedded::getAddress).get(AddressWithZip::getCity),
                    EntityPath.of(PersonWithCrossLayerEmbedded::getAddress).get(AddressWithZip::getStreet),
                    EntityPath.of(PersonWithCrossLayerEmbedded::getAddress).get(AddressWithZip::getZip).get(ZipCode::getCode),
                    EntityPath.of(PersonWithCrossLayerEmbedded::getSecondaryZip).get(ZipCode::getCode)),
            row -> {
                PersonCrossLayerBasic dto = new PersonCrossLayerBasic();
                dto.setId((Long) row.get(0));
                dto.setName((String) row.get(1));
                dto.setCity((String) row.get(2));
                dto.setStreet((String) row.get(3));
                dto.setZipCode((String) row.get(4));
                dto.setSecondaryCode((String) row.get(5));
                return dto;
            });

    private static final ProjectionMapper<PersonWithAddress, PersonAddressOnlyEmbedded> PERSON_ADDRESS_ONLY_EMBEDDED = mapper(
            List.of(
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getStreet),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getCity),
                    EntityPath.of(PersonWithAddress::getAddress).get(Address::getZipCode)),
            row -> {
                PersonAddressOnlyEmbedded dto = new PersonAddressOnlyEmbedded();
                dto.setStreet((String) row.get(0));
                dto.setCity((String) row.get(1));
                dto.setZipCode((String) row.get(2));
                return dto;
            });

    private static final ProjectionMapper<PersonWithAddress, ProjectEmbeddedOnly> PROJECT_EMBEDDED_ONLY = mapper(
            List.of(EntityPath.of(PersonWithAddress::getAddress).get(Address::getCity)),
            row -> {
                ProjectEmbeddedOnly dto = new ProjectEmbeddedOnly();
                dto.setCity((String) row.get(0));
                return dto;
            });

    /// 3.x 的 DTO 投影入口 `project(ProjectionMapper)`：列序即 `expressions()` 序，`map(Tuple)` 按序取值。
    private static <T, R> ProjectionMapper<T, R> mapper(List<Expression<T, ?>> columns, Function<Tuple, R> factory) {
        return new ProjectionMapper<T, R>() {
            @Override
            public List<Expression<T, ?>> expressions() {
                return columns;
            }

            @Override
            public R map(Tuple row) {
                return factory.apply(row);
            }
        };
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project flattened embedded fields to JavaBean via ProjectionMapper")
    void shouldProjectEmbeddedToJavaBeanWithEntityPath(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insert(
                new PersonWithAddress(80000L, "Alice", new Address("123 Main St", "Springfield", "12345")));

        List<PersonAddressBasic> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_BASIC)
                .where(PersonWithAddress::getId).eq(80000L)
                .list();

        assertThat(results).hasSize(1);
        PersonAddressBasic dto = results.getFirst();
        assertThat(dto.getId()).isEqualTo(80000L);
        assertThat(dto.getName()).isEqualTo("Alice");
        assertThat(dto.getStreet()).isEqualTo("123 Main St");
        assertThat(dto.getCity()).isEqualTo("Springfield");
        assertThat(dto.getZipCode()).isEqualTo("12345");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project flattened embedded fields to Record via ProjectionMapper")
    void shouldProjectEmbeddedToRecordWithEntityPath(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insert(
                new PersonWithAddress(80001L, "Bob", new Address("456 Oak Ave", "Metropolis", "67890")));

        List<PersonAddressRecord> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_RECORD)
                .where(PersonWithAddress::getId).eq(80001L)
                .list();

        assertThat(results).hasSize(1);
        PersonAddressRecord record = results.getFirst();
        assertThat(record.id()).isEqualTo(80001L);
        assertThat(record.name()).isEqualTo("Bob");
        assertThat(record.street()).isEqualTo("456 Oak Ave");
        assertThat(record.city()).isEqualTo("Metropolis");
        assertThat(record.zipCode()).isEqualTo("67890");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should filter by embedded field and project via ProjectionMapper")
    void shouldFilterByEmbeddedFieldWithProjection(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insertAll(List.of(
                new PersonWithAddress(80010L, "Dave", new Address("Elm St", "Springfield", "111")),
                new PersonWithAddress(80011L, "Eve", new Address("Oak Ave", "Metropolis", "222"))
        ));

        List<PersonAddressBasic> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_BASIC)
                .where(EntityPath.of(PersonWithAddress::getAddress).get(Address::getCity)).eq("Springfield")
                .orderBy(PersonWithAddress::getId).asc()
                .list();

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getName()).isEqualTo("Dave");
        assertThat(results.getFirst().getCity()).isEqualTo("Springfield");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project flattened nested embedded fields via ProjectionMapper")
    void shouldProjectNestedEmbeddedWithEntityPath(IntegrationTestContext context) {
        Address addr = new Address("999 Deep Rd", "DeepCity", "77777");
        ContactInfo contact = new ContactInfo("grace@test.com", "555-9999", addr);
        context.operations(PersonWithNestedAddress.class).insert(
                new PersonWithNestedAddress(80040L, "Grace", contact));

        List<PersonContactBasic> results = context.queryPersonWithNestedAddresses()
                .project(PERSON_CONTACT_BASIC)
                .where(PersonWithNestedAddress::getId).eq(80040L)
                .list();

        assertThat(results).hasSize(1);
        PersonContactBasic dto = results.getFirst();
        assertThat(dto.getId()).isEqualTo(80040L);
        assertThat(dto.getName()).isEqualTo("Grace");
        assertThat(dto.getEmail()).isEqualTo("grace@test.com");
        assertThat(dto.getPhone()).isEqualTo("555-9999");
        assertThat(dto.getStreet()).isEqualTo("999 Deep Rd");
        assertThat(dto.getCity()).isEqualTo("DeepCity");
        assertThat(dto.getZipCode()).isEqualTo("77777");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project @AttributeOverride renamed columns via ProjectionMapper")
    void shouldProjectOverriddenEmbeddedWithEntityPath(IntegrationTestContext context) {
        context.operations(PersonWithOverriddenAddress.class).insert(
                new PersonWithOverriddenAddress(80050L, "Henry",
                        new Address("Override St", "OverrideCity", "99999")));

        List<PersonAddressOverridden> results = context.queryPersonWithOverriddenAddresses()
                .project(PERSON_ADDRESS_OVERRIDDEN)
                .where(PersonWithOverriddenAddress::getId).eq(80050L)
                .list();

        assertThat(results).hasSize(1);
        PersonAddressOverridden dto = results.getFirst();
        assertThat(dto.getId()).isEqualTo(80050L);
        assertThat(dto.getName()).isEqualTo("Henry");
        assertThat(dto.getStreet()).isEqualTo("Override St");
        assertThat(dto.getCity()).isEqualTo("OverrideCity");
        assertThat(dto.getZipCode()).isEqualTo("99999");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project cross-layer embedded fields via ProjectionMapper")
    void shouldProjectCrossLayerEmbeddedWithEntityPath(IntegrationTestContext context) {
        context.operations(PersonWithCrossLayerEmbedded.class).insert(
                new PersonWithCrossLayerEmbedded(80060L, "Isaac",
                        new AddressWithZip("CrossCity", "Cross St", new ZipCode("94105")),
                        new ZipCode("12345")));

        List<PersonCrossLayerBasic> results = context.queryPersonWithCrossLayerEmbedded()
                .project(PERSON_CROSS_LAYER_BASIC)
                .where(PersonWithCrossLayerEmbedded::getId).eq(80060L)
                .list();

        assertThat(results).hasSize(1);
        PersonCrossLayerBasic dto = results.getFirst();
        assertThat(dto.getId()).isEqualTo(80060L);
        assertThat(dto.getName()).isEqualTo("Isaac");
        assertThat(dto.getCity()).isEqualTo("CrossCity");
        assertThat(dto.getStreet()).isEqualTo("Cross St");
        assertThat(dto.getZipCode()).isEqualTo("94105");
        assertThat(dto.getSecondaryCode()).isEqualTo("12345");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should project null embedded to DTO with null fields")
    void shouldProjectNullEmbeddedToDtoWithNullFields(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insert(
                new PersonWithAddress(80090L, "NullAddr", null));

        List<PersonAddressBasic> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_BASIC)
                .where(PersonWithAddress::getId).eq(80090L)
                .list();

        assertThat(results).hasSize(1);
        PersonAddressBasic dto = results.getFirst();
        assertThat(dto.getId()).isEqualTo(80090L);
        assertThat(dto.getName()).isEqualTo("NullAddr");
        assertThat(dto.getStreet()).isNull();
        assertThat(dto.getCity()).isNull();
        assertThat(dto.getZipCode()).isNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should support embedded projection with orderBy on root field")
    void shouldSupportEmbeddedProjectionWithOrderBy(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insertAll(List.of(
                new PersonWithAddress(80100L, "Charlie", new Address("1st St", "Alpha", "001")),
                new PersonWithAddress(80101L, "Alice", new Address("2nd Ave", "Beta", "002")),
                new PersonWithAddress(80102L, "Bob", new Address("3rd Blvd", "Gamma", "003"))
        ));

        List<PersonAddressBasic> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_BASIC)
                .where(PersonWithAddress::getId).in(80100L, 80101L, 80102L)
                .orderBy(PersonWithAddress::getName).asc()
                .list();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getName()).isEqualTo("Alice");
        assertThat(results.get(0).getCity()).isEqualTo("Beta");
        assertThat(results.get(1).getName()).isEqualTo("Bob");
        assertThat(results.get(1).getCity()).isEqualTo("Gamma");
        assertThat(results.get(2).getName()).isEqualTo("Charlie");
        assertThat(results.get(2).getCity()).isEqualTo("Alpha");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return non-null DTO with null fields when all embedded fields are null")
    void shouldReturnDtoWithNullFieldsWhenAllEmbeddedFieldsNull(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insert(
                new PersonWithAddress(80120L, "AllNull", null));

        List<PersonAddressOnlyEmbedded> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_ONLY_EMBEDDED)
                .where(PersonWithAddress::getId).eq(80120L)
                .list();

        assertThat(results).hasSize(1);
        PersonAddressOnlyEmbedded dto = results.getFirst();
        assertThat(dto).isNotNull();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should support pagination with embedded projection")
    void shouldProjectEmbeddedWithPagination(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insertAll(List.of(
                new PersonWithAddress(80070L, "Jack", new Address("1st St", "CityA", "001")),
                new PersonWithAddress(80071L, "Kate", new Address("2nd Ave", "CityB", "002")),
                new PersonWithAddress(80072L, "Leo", new Address("3rd Blvd", "CityC", "003"))
        ));

        List<PersonAddressBasic> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_BASIC)
                .where(PersonWithAddress::getId).in(80070L, 80071L, 80072L)
                .orderBy(PersonWithAddress::getId).asc()
                .list(2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getName()).isEqualTo("Jack");
        assertThat(results.get(1).getName()).isEqualTo("Kate");
        assertThat(results.get(0).getCity()).isEqualTo("CityA");
        assertThat(results.get(1).getCity()).isEqualTo("CityB");
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should return empty list when no match")
    void shouldReturnEmptyForNonExistent(IntegrationTestContext context) {
        List<PersonAddressBasic> results = context.queryPersonWithAddresses()
                .project(PERSON_ADDRESS_BASIC)
                .where(PersonWithAddress::getId).eq(99999L)
                .list();

        assertThat(results).isEmpty();
    }

    @ParameterizedTest
    @ArgumentsSource(IntegrationTestProvider.class)
    @DisplayName("Should support selectDistinct with embedded projection")
    void shouldSelectDistinctEmbeddedProjection(IntegrationTestContext context) {
        context.operations(PersonWithAddress.class).insertAll(List.of(
                new PersonWithAddress(80080L, "Mike", new Address("Same St", "SameCity", "001")),
                new PersonWithAddress(80081L, "Nina", new Address("Same St", "SameCity", "001")),
                new PersonWithAddress(80082L, "Oscar", new Address("Other St", "OtherCity", "002"))
        ));

        // Use ProjectEmbeddedOnly (projects only address.city) so DISTINCT actually deduplicates:
        // 2 of 3 records share city="SameCity" -> only 2 distinct values returned
        List<ProjectEmbeddedOnly> results = context.queryPersonWithAddresses()
                .distinct().project(PROJECT_EMBEDDED_ONLY)
                .where(PersonWithAddress::getId).in(80080L, 80081L, 80082L)
                .list();

        assertThat(results).hasSize(2);
        assertThat(results).extracting(ProjectEmbeddedOnly::getCity)
                .containsExactlyInAnyOrder("OtherCity", "SameCity");
    }
}
