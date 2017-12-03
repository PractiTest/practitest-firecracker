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
