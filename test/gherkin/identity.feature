Feature: Identity feature
    Scenario: Simple test for identity
        Given I run identity function with 1
        Then I will get result 1

    Scenario Outline: Multiple identity tests for <First Arg> and <Second Arg> with "Some quoutes"
        When I run identity function with <First Arg>
        Then I will get result <Second Arg> every time

    Examples:

            | First Arg     | Second Arg    |
            | "First Item"  | "First Item"  |
            | "Second Item" | "Second 123"  |
            | "Third Item"  | "Third Item"  |

    Examples:

            | First Arg     | Second Arg    |
            | "Second Part" | "Second Part" |
            | "Failure"     | "That would fail" |

    Scenario: Another schenario just in case
