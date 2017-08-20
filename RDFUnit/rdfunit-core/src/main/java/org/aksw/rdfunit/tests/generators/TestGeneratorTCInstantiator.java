package org.aksw.rdfunit.tests.generators;

import com.google.common.collect.ImmutableList;
import org.aksw.jena_sparql_api.model.QueryExecutionFactoryModel;
import org.aksw.rdfunit.enums.TestGenerationType;
import org.aksw.rdfunit.model.impl.PatternBasedTestCaseImpl;
import org.aksw.rdfunit.model.interfaces.*;
import org.aksw.rdfunit.services.PrefixNSService;
import org.aksw.rdfunit.sources.SchemaSource;
import org.aksw.rdfunit.tests.TestCaseValidator;
import org.aksw.rdfunit.utils.TestUtils;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;

/**
 * Instantiates TestCases based on a test generator and a schema
 *
 * @author Dimitris Kontokostas
 * @version $Id: $Id
 * @since 9/26/15 1:23 PM
 */
public class TestGeneratorTCInstantiator {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestGeneratorTCInstantiator.class);


    private final ImmutableList<TestGenerator> testGenerators;
    private final SchemaSource source;


    public TestGeneratorTCInstantiator(Collection<TestGenerator> testGenerators, SchemaSource source) {
        this.testGenerators = ImmutableList.copyOf(testGenerators);
        this.source = source;
    }


    public Collection<TestCase> generate() {


        Collection<TestCase> tests = new ArrayList<>();

        for (TestGenerator tg : testGenerators) {
            Pattern tgPattern = tg.getPattern();

            Query q = QueryFactory.create(PrefixNSService.getSparqlPrefixDecl() + tg.getQuery());
            QueryExecution qe = new QueryExecutionFactoryModel(source.getModel()).createQueryExecution(q);
            qe.execSelect().forEachRemaining(result -> {

                Optional<TestCase> tc = generateTestFromResult(tg, tgPattern, result);
                if (tc.isPresent()) {
                    tests.add(tc.get());
                }

            });
        }
        return tests;
    }

    private Optional<TestCase> generateTestFromResult(TestGenerator tg, Pattern tgPattern, QuerySolution row) {
        Collection<String> references = new ArrayList<>();
        String description;

        Collection<Binding> bindings = new ArrayList<>();
        for (PatternParameter p : tgPattern.getParameters()) {
            if (row.contains(p.getId())) {
                RDFNode n = row.get(p.getId());
                Binding b;
                try {
                    b = new Binding(p, n);
                } catch (NullPointerException | IllegalArgumentException e) {
                    LOGGER.error("Non valid binding for parameter {} in AutoGenerator: {}", p.getId(), tg.getUri(), e);
                    continue;
                }
                bindings.add(b);
                if (n.isResource() && !"loglevel".equalsIgnoreCase(p.getId())) {
                    references.add(n.toString().trim().replace(" ", ""));
                }
            } else {
                LOGGER.error("Not bindings for parameter {} in AutoGenerator: {}", p.getId(), tg.getUri());
                break;
            }
        }
        if (bindings.size() != tg.getPattern().getParameters().size()) {
            LOGGER.error("Bindings for pattern {} do not match in AutoGenerator: {}", tgPattern.getId(), tg.getUri());
            return Optional.empty();
        }

        if (row.get("DESCRIPTION") != null) {
            description = row.get("DESCRIPTION").toString();
        } else {
            LOGGER.error("No ?DESCRIPTION variable found in AutoGenerator: {}", tg.getUri());
            return Optional.empty();
        }


        Collection<ResultAnnotation> patternBindedAnnotations = tgPattern.getBindedAnnotations(bindings);
        patternBindedAnnotations.addAll(tg.getAnnotations());
        Resource tcResource = ResourceFactory.createResource(TestUtils.generateTestURI(source.getPrefix(), tgPattern, bindings, tg.getUri()));
        PatternBasedTestCaseImpl tc = new PatternBasedTestCaseImpl(
                tcResource,
                new TestCaseAnnotation(
                        tcResource, TestGenerationType.AutoGenerated,
                        tg.getUri(),
                        source.getSourceType(),
                        source.getUri(),
                        references,
                        description,
                        null,
                        patternBindedAnnotations),
                tgPattern,
                bindings
        );
        new TestCaseValidator(tc).validate();
        return Optional.of(tc);
    }
}
