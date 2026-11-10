rootProject.name = "bank-java"

// One module per way of learning that a payment arrived. A bank's API client (Fio, ...) joins here as its own module.
include(":modules:bank-notifications")
include(":modules:bank-notifications-dkim")
