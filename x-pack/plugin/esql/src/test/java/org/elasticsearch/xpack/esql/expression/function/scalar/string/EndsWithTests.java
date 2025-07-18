/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.xpack.esql.capabilities.TranslationAware;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.querydsl.query.WildcardQuery;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.expression.function.AbstractScalarFunctionTestCase;
import org.elasticsearch.xpack.esql.expression.function.TestCaseSupplier;
import org.elasticsearch.xpack.esql.optimizer.rules.physical.local.LucenePushdownPredicates;
import org.elasticsearch.xpack.esql.planner.TranslatorHandler;
import org.hamcrest.Matcher;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;

public class EndsWithTests extends AbstractScalarFunctionTestCase {
    public EndsWithTests(@Name("TestCase") Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        List<TestCaseSupplier> suppliers = new LinkedList<>();
        for (DataType strType : DataType.stringTypes()) {
            for (DataType suffixType : DataType.stringTypes()) {
                suppliers.add(
                    new TestCaseSupplier(
                        "<" + strType + ">, empty <" + suffixType + ">",
                        List.of(strType, suffixType),
                        () -> testCase(strType, suffixType, randomAlphaOfLength(5), "", equalTo(true))
                    )
                );
                suppliers.add(
                    new TestCaseSupplier(
                        "empty <" + strType + ">, <" + suffixType + ">",
                        List.of(strType, suffixType),
                        () -> testCase(strType, suffixType, "", randomAlphaOfLength(5), equalTo(false))
                    )
                );
                suppliers.add(
                    new TestCaseSupplier("<" + strType + ">, one char <" + suffixType + "> matches", List.of(strType, suffixType), () -> {
                        String str = randomAlphaOfLength(5);
                        String suffix = randomAlphaOfLength(1);
                        str = str + suffix;
                        return testCase(strType, suffixType, str, suffix, equalTo(true));
                    })
                );
                suppliers.add(
                    new TestCaseSupplier("<" + strType + ">, one char <" + suffixType + "> differs", List.of(strType, suffixType), () -> {
                        String str = randomAlphaOfLength(5);
                        String suffix = randomAlphaOfLength(1);
                        str = str + randomValueOtherThan(suffix, () -> randomAlphaOfLength(1));
                        return testCase(strType, suffixType, str, suffix, equalTo(false));
                    })
                );
                suppliers.add(
                    new TestCaseSupplier("random <" + strType + ">, random <" + suffixType + ">", List.of(strType, suffixType), () -> {
                        String str = randomAlphaOfLength(5);
                        String suffix = randomAlphaOfLength(3);
                        return testCase(strType, suffixType, str, suffix, equalTo(str.endsWith(suffix)));
                    })
                );
            }
        }
        return parameterSuppliersFromTypedDataWithDefaultChecksNoErrors(true, suppliers);
    }

    private static TestCaseSupplier.TestCase testCase(
        DataType strType,
        DataType suffixType,
        String str,
        String suffix,
        Matcher<Boolean> matcher
    ) {
        return new TestCaseSupplier.TestCase(
            List.of(
                new TestCaseSupplier.TypedData(new BytesRef(str), strType, "str"),
                new TestCaseSupplier.TypedData(new BytesRef(suffix), suffixType, "suffix")
            ),
            "EndsWithEvaluator[str=Attribute[channel=0], suffix=Attribute[channel=1]]",
            DataType.BOOLEAN,
            matcher
        );
    }

    @Override
    protected Expression build(Source source, List<Expression> args) {
        return new EndsWith(source, args.get(0), args.get(1));
    }

    public void testLuceneQuery_AllLiterals_NonTranslatable() {
        var function = new EndsWith(Source.EMPTY, Literal.keyword(Source.EMPTY, "test"), Literal.keyword(Source.EMPTY, "test"));

        assertThat(function.translatable(LucenePushdownPredicates.DEFAULT), equalTo(TranslationAware.Translatable.NO));
    }

    public void testLuceneQuery_NonFoldableSuffix_NonTranslatable() {
        var function = new EndsWith(
            Source.EMPTY,
            new FieldAttribute(Source.EMPTY, "field", new EsField("field", DataType.KEYWORD, Map.of(), true)),
            new FieldAttribute(Source.EMPTY, "field", new EsField("suffix", DataType.KEYWORD, Map.of(), true))
        );

        assertThat(function.translatable(LucenePushdownPredicates.DEFAULT), equalTo(TranslationAware.Translatable.NO));
    }

    public void testLuceneQuery_NonFoldableSuffix_Translatable() {
        var function = new EndsWith(
            Source.EMPTY,
            new FieldAttribute(Source.EMPTY, "field", new EsField("suffix", DataType.KEYWORD, Map.of(), true)),
            Literal.keyword(Source.EMPTY, "a*b?c\\")
        );

        assertThat(function.translatable(LucenePushdownPredicates.DEFAULT), equalTo(TranslationAware.Translatable.YES));

        var query = function.asQuery(LucenePushdownPredicates.DEFAULT, TranslatorHandler.TRANSLATOR_HANDLER);

        assertThat(query, equalTo(new WildcardQuery(Source.EMPTY, "field", "*a\\*b\\?c\\\\", false, false)));
    }
}
