# practitest-firecracker

You will need to use the 'create-testset' action once and the 'populate-testset' action every time there is a new report.
If the structure of the report changes (new tests are added for example), you will need to use the 'create-testset' action again. New TestSets will be created, but existing tests will be reused.

If you don't have an existing CONFIG_FILE and you want to use it, go to https://firecracker-ui-prod.practitest.com/ and generate one.
To login, you can use your PractiTest credentials and follow the instructions in the link. Then you can continue here and set the config-path to your configuration file path.
## Usage

### help

At every point you can run:

``` shell
java -jar practitest-firecracker-standalone.jar help
```
to get more information about the parameters and commands.

### create-testset

Create new testset from reports folder:

``` shell
java -jar practitest-firecracker-standalone.jar \
    --reports-path=SUREFIRE_REPORTS_PATH \
    --testset-name="TestSet name" \
    --author-id=PRACTITEST_USER_ID \
    --config-path=CONFIG_FILE \
    create-testset
```

The call above will analyze the surefire reports and create tests and the testset. If a test already exists, it will be reused.

You can set various custom fields for tests when they are created (especially useful if you have mandatory fields configured in your tests).

To set custom fields you will need to create a configuration file in here: https://firecracker-ui-prod.practitest.com/ .After that you can use it to run the command line above with it (CONFIG_FILE).

### populate-testset
Populate the testset from reports folder:

``` shell
java -jar practitest-firecracker-standalone.jar \
    --reports-path=SUREFIRE_REPORTS_PATH \
    --testset-id=PRACTITEST_TESTSET_ID \
    --author-id=PRACTITEST_USER_ID \
    --config-path=CONFIG_FILE \
    populate-testset
```

### create-and-populate-testset

This will attempt to do the two actions above at once. It will search for a TestSet with the given name. If the TestSet doesn't exist, it will create one. If it does exist but some tests are not part of the TestSet, it will add the missing tests. Then it will populate the TestSet. It will print the TestSet ID (or error) when done.

Example:

``` shell
java -jar practitest-firecracker-standalone.jar \
    --reports-path=SUREFIRE_REPORTS_PATH \
    --testset-name="TestSet name" \
    --author-id=PRACTITEST_USER_ID \
    --config-path=CONFIG_FILE \
    create-and-populate-testset
```
### use Firecracker without config file

You can use all the above commands without the config file. You will need to explicitly define
parameters:
--api-token=YOUR_API_TOKEN
--email=YOUR_EMAIL
--project-id=PRACTITEST_PROJECT_ID
--additional-test-fields '{"custom-fields": {"---f-123": "foo", "---f-124": "bar"}, "system-fields"{"version": "2.3", "status":"Draft"}}'
--additional-testset-fields '{"custom-fields": {"---f-125": "baz"}, "system-fields"{"version": "1.0", "assigned-to-id": "1"}}
if they are relevant to the run (additional-fields not required).


Example:

``` shell
java -jar practitest-firecracker-standalone.jar \
    --api-token=YOUR_API_TOKEN \
    --email=YOUR_EMAIL \
    --reports-path=SUREFIRE_REPORTS_PATH \
    --project-id=PRACTITEST_PROJECT_ID \
    --testset-name="TestSet name" \
    --author-id=PRACTITEST_USER_ID \
    --additional-test-fields '{"custom-fields": {"---f-123": "foo", "---f-124": "bar"}}' \
    --additional-testset-fields '{"custom-fields": {"---f-125": "baz"}}' \
    create-and-populate-testset
```

You can set various custom fields for tests when they are created (especially useful if you have mandatory fields configured in your tests).

Replace the field ids with the actual IDs, you can see all your custom fields by calling this API call: [get-all-custom-fields-in-your-project](https://www.practitest.com/api-v2/#get-all-custom-fields-in-your-project)

## License

Copyright © 2017 PractiTest.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
