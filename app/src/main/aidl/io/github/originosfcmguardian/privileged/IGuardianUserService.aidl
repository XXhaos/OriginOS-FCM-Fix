package io.github.originosfcmguardian.privileged;

import android.os.Bundle;

interface IGuardianUserService {
    void destroy() = 16777114;
    int getServiceUid() = 1;
    Bundle execute(long requestId, String operationId, int uidArgument, String packageArgument) = 2;
    void cancel(long requestId) = 3;
}
