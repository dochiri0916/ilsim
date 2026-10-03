import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


templates = load('convention_templates')
ask = load('ask')
FULL = """dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    compileOnly 'org.projectlombok:lombok'
}
"""


class ProvideTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.project = Path(temp.name)
        main = self.project/'src/main/java/com/acme/shop'
        main.mkdir(parents=True)
        (main/'ShopApplication.java').write_text('package com.acme.shop;\n\n@SpringBootApplication\npublic class ShopApplication {}\n')

    def build(self, text):
        (self.project/'build.gradle').write_text(text)

    def test_copies_globals_under_the_project_package_with_their_tests_and_properties(self):
        # given
        self.build(FULL)
        # when
        written = templates.provide(self.project)
        # then
        self.assertIn('src/main/java/com/acme/shop/global/exception/ProblemDetails.java', written)
        self.assertIn('src/main/java/com/acme/shop/global/persistence/BaseEntity.java', written)
        self.assertIn('src/test/java/com/acme/shop/global/persistence/RepositoryExceptionTranslatorTest.java', written)
        advice = (self.project/'src/main/java/com/acme/shop/global/exception/GlobalApiControllerAdvice.java').read_text()
        self.assertIn('package com.acme.shop.global.exception;', advice)
        self.assertNotIn('com.example.demo', advice)
        properties = (self.project/'src/main/resources/application.properties').read_text()
        self.assertIn('spring.aop.proxy-target-class=false', properties)
        self.assertIn('spring.jpa.open-in-view=false', properties)
        self.assertEqual([], templates.provide(self.project))

    def test_skips_files_whose_dependency_or_global_type_is_missing(self):
        # given: no JPA, and the project has its own ProblemDetails
        self.build(FULL.replace('data-jpa', 'validation'))
        own = self.project/'src/main/java/com/acme/shop/web/ProblemDetails.java'
        own.parent.mkdir(parents=True)
        own.write_text('package com.acme.shop.web;\npublic class ProblemDetails {}\n')
        (self.project/'src/main/resources').mkdir(parents=True)
        (self.project/'src/main/resources/application.properties').write_text('spring.aop.proxy-target-class=true')
        # when
        written = templates.provide(self.project)
        # then
        self.assertEqual(['src/main/java/com/acme/shop/global/application/exception/RepositoryOperationException.java',
                          'src/main/java/com/acme/shop/global/configuration/TimeConfiguration.java'], written)

    def test_project_without_a_boot_application_is_left_alone(self):
        (self.project/'src/main/java/com/acme/shop/ShopApplication.java').write_text('package com.acme.shop;\nclass ShopApplication {}\n')
        self.build(FULL)
        self.assertEqual([], templates.provide(self.project))
        self.assertFalse((self.project/'src/main/resources').exists())

    def test_provide_globals_adds_the_new_tests_to_the_regression_plan(self):
        # given
        self.build(FULL)
        plan = self.project/'plan.json'
        plan.write_text(json.dumps({'project': str(self.project), 'features': {'existing-tests': ['a.ATest#x']}}))
        value = {'convention': {'project': str(self.project)}, 'config': {'plan': str(plan)}}
        # when
        ask.provide_globals(value)
        # then
        tests = json.loads(plan.read_text())['features']['existing-tests']
        self.assertTrue(any(test.startswith('com.acme.shop.global.persistence.RepositoryExceptionTranslatorTest#') for test in tests))


class GuidanceTests(unittest.TestCase):
    def test_tree_always_and_example_code_only_when_asked(self):
        short, full = templates.guidance(examples=False), templates.guidance()
        self.assertIn('main/java/com/example/demo/book/domain/event/BookRegistered.java', short)
        self.assertNotIn('```java', short)
        self.assertIn('public record BookRegistered', full)
        self.assertLess(len(short), len(full))


if __name__ == '__main__':
    unittest.main()
