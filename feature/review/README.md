# :feature:review

Owned by developer 2.

This module will be the per-item review screen. For each flagged item the user will choose:

- keep
- move to an in-app locked folder
- send to the system trash with `MediaStore.createTrashRequest` (API 30)

There is no deletion code yet. Do not request `MANAGE_EXTERNAL_STORAGE` or `MANAGE_MEDIA`. `createTrashRequest` asks the user in a system dialog and does not need either permission. Do not add `INTERNET`.
