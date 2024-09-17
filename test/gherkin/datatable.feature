Feature: Using BDD data tables
    Scenario: Testing data table
        Given some initial data:
            | item1 | item2 |
            |  arg  |  val  |
            |  arg2 |  val2 |
        When I invoke my super duper program
        Then I can see "val" and "val2" there
