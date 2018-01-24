# practitest-firecracker

You will need to use 'create-testset' action once and 'populate-testset' action every time there is a new report.
If the structure of the report changes (new tests added for example), you will need to use 'create-testset' action again. New TestSet will be created, but existing tests will be reused.

## Usage

Create new testset from reports folder:

``` shell
java -jar practitest-firecracker-standaline.jar \
    --api-token=YOUR_API_TOKEN \
    --email=YOUR_EMAIL \
    --reports-path=SUREFIRE_REPORTS_PATH \
    --project-id=PRACTITEST_PROJECT_ID \
    --testset-name="TestSet name" \
    --author-id=PRACTITEST_USER_ID \
    create-testset
```

The call above will analyze the surefire reports and create tests and testset. If a test already exists, it will be reused.
You can set various custom fields for tests when they are created (especially useful if you have mandatory fields configured in your tests).
Here is an example for setting custom fields during creation of the tests and testset:

``` shell
java -jar practitest-firecracker-standaline.jar \
    --api-token=YOUR_API_TOKEN \
    --email=YOUR_EMAIL \
    --reports-path=SUREFIRE_REPORTS_PATH \
    --project-id=PRACTITEST_PROJECT_ID \
    --testset-name="TestSet name" \
    --author-id=PRACTITEST_USER_ID \
    --additional-test-fields '{"custom-fields": {"---f-123": "foo", "---f-124": "bar"}}' \
    --additional-testset-fields '{"custom-fields": {"---f-125": "baz"}}' \
    create-testset

```

Replace the field ids with the actual IDs, you can see all your custom fields by calling this API call: [get-all-custom-fields-in-your-project](https://www.practitest.com/api-v2/#get-all-custom-fields-in-your-project)

Populate the testset from reports folder:

``` shell
java -jar practitest-firecracker-standaline.jar \
    --api-token=YOUR_API_TOKEN \
    --email=YOUR_EMAIL \
    --reports-path=SUREFIRE_REPORTS_PATH \
    --project-id=PRACTITEST_PROJECT_ID \
    --testset-id=PRACTITEST_TESTSET_ID \
    populate-testset
```

## License

Copyright © 2017 PractiTest.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
