import { useAuth } from "./AuthContext";
import { canMutate, mutationBlockedReason } from "./auth";

export function useMutationAccess() {
  const { me, loading } = useAuth();
  const blocked = mutationBlockedReason(loading ? null : me);
  return {
    allowMutations: !loading && canMutate(me),
    blocked,
  };
}
