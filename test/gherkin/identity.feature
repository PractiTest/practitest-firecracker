Feature: Identity feature
    Scenario: Simple test for identity
        Given I run identity function with 1
        Then I will get result 1

    Scenario Outline: Multiple identity tests for <arg> and <result> with "Some quoutes"
        When I run identity function with <arg>
        Then I will get result <result> every time

    Examples:
            | arg           | result        |
            | "First Item"  | "First Item"  |
            | "Second Item" | "Second 123" |
            | "Third Item"  | "Third Item"  |
