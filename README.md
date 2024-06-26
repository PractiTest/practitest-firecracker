# practitest-firecracker

You will need to use the 'create-and-populate-testset' action that will create and populate TestSets, Tests, Instances and Runs.
If the structure of the report changes (new tests are added for example), you will need to use the 'create-and-populate-testset' action again. New TestSets will be created, but existing tests will be reused.

If you don't have an existing CONFIG_FILE and you want to use it, go to https://firecracker-ui-prod.practitest.com/ and generate one.
To login, you can use your PractiTest credentials and follow the instructions in the link. Then you can continue here and set the config-path to your configuration file path.

## Pre-requisites

- JDK 11
- Babashka
- Clojure CLI

## REPL

```shell
bb dev
```

## Building uberjar

```shell
bb release
```

## Usage

### help

At every point you can run:

```shell
java -jar practitest-firecracker-standalone.jar help
```

to get more information about the parameters and commands.

### version

To check your jar version you can you this command:

```shell
java -jar practitest-firecracker-standalone.jar version
```

### create-and-populate-testset

This will attempt to do the two actions above at once. It will search for a TestSet with the given name. If the TestSet doesn't exist, it will create one. If it does exist but some tests are not part of the TestSet, it will add the missing tests. Then it will populate the TestSet. It will print the TestSet ID (or error) when done.

Example:

```shell
java -jar practitest-firecracker-standalone.jar \
    --reports-path=REPORTS_FOLDER_PATH \
    --author-id=PRACTITEST_USER_ID \
    --config-path=CONFIG_FILE \
    create-and-populate-testset
```

- author-id is not required in case of PAT (personal api token) is in use

The call above will analyze the surefire reports and create and populate tests and the testset. If a test already exists, it will be reused.

You can set various custom fields for tests when they are created (especially useful if you have mandatory fields configured in your tests).

To set custom fields you will need to create a configuration file in here: https://firecracker-ui-prod.practitest.com/ .After that you can use it to run the command line above with it (CONFIG_FILE).

### use Firecracker without config file

You can use all the above commands without the config file. You will need to explicitly define
parameters:
--api-token=YOUR_API_TOKEN
--email=YOUR_EMAIL
--testset-name=TESTSET_NAME
--project-id=PRACTITEST_PROJECT_ID
--additional-test-fields '{"custom-fields": {"---f-123": "foo", "---f-124": "bar"}, "system-fields"{"version": "2.3", "status":"Draft"}}'
--additional-testset-fields '{"custom-fields": {"---f-125": "baz"}, "system-fields"{"version": "1.0", "assigned-to-id": "1"}}
--additional-run-fields '{"custom-fields": {"---f-124": "test"}, "system-fields"{}}
if they are relevant to the run (additional-fields not required).

Example:

```shell
java -jar practitest-firecracker-standalone.jar \
    --api-token=YOUR_API_TOKEN \
    --email=YOUR_EMAIL \
    --testset-name=TESTSET_NAME \
    --reports-path=REPORTS_FOLDER_PATH \
    --project-id=PRACTITEST_PROJECT_ID \
    --testset-name="TestSet name" \
    --author-id=PRACTITEST_USER_ID \
    --additional-test-fields '{"custom-fields": {"---f-123": "foo", "---f-124": "bar"}}' \
    --additional-testset-fields '{"custom-fields": {"---f-125": "baz"}}' \
    --additional-run-fields '{"custom-fields": {"---f-124": "test"}, "system-fields"{}}' \
    create-and-populate-testset
```

You can set various custom fields for tests when they are created (especially useful if you have mandatory fields configured in your tests).

Replace the field ids with the actual IDs, you can see all your custom fields by calling this API call: [get-all-custom-fields-in-your-project](https://www.practitest.com/api-v2/#get-all-custom-fields-in-your-project)

### Additional options

If you want to use firecracker custom uris you will need to add this --api-uri parameter like this
for stage:
--api-uri=https://stage.practitest.com/
for EU:
--api-uri=https://eu1-prod-api.practitest.app
for local:
--api-uri=http://localhost:PORT_NUM

To display action logs like create/update etc. add --display-action-logs=true (it will make the run slower so only add it for manual runs)

In case you have very big xml test results and it take very long time for firecracker to complete add --max-api-rate=NUM (the default is 30)
!!!important!!! make sure that you add the same limit to of api rate limit to your account.

In case you want the name of the test to take its value from other attribute of testcase(s) add this parameter --pt-test-name=?ATTRIBUTE for example --pt-test-name=?full-class-name you can see the values that acceptable in firecracker UI (default is ?pt-test-name)

Same goes for --pt-test-step-name that will define the name of the steps.

--multitestset option will point if we are using multitestset file if it is true we will take the testsetname from
the name attribute from inside the file.

--test-case-as-pt-test-step will declare if we want to set testcase as single test or as a group of testcases.

For more information contact our support.

## License

Copyright © 2017 PractiTest.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
