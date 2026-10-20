// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { PrivacyContent } from "./PrivacyContent"
import { BRAND } from "@/lib/brand"

export const metadata = {
  title: `Privacy and data protection — ${BRAND.name} Admin`,
}

export default function PrivacyPage() {
  return <PrivacyContent />
}
