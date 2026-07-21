import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import type { Driver } from '@/types';

export const DRIVERS_QUERY_KEY = ['drivers'] as const;

export interface DriverPayload {
  name: string;
  phone: string;
  email?: string;
}

interface DriverStatusBody {
  isRegistered: boolean;
  reason?: string;
}

interface CancelInviteBody {
  reason?: string;
}

export function useDrivers() {
  return useQuery<Driver[]>({
    queryKey: DRIVERS_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<Driver[]>('/admin/drivers');
      return Array.isArray(res.data) ? res.data : [];
    },
    retry: 1,
    staleTime: 30000,
  });
}

export function useCreateDriver() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: DriverPayload) => {
      const res = await api.post<Driver>('/admin/drivers', payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDriverCreated');
      queryClient.invalidateQueries({ queryKey: DRIVERS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDriverCreateFailed');
    },
  });
}

export function useUpdateDriver() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: Partial<DriverPayload> }) => {
      const res = await api.put<Driver>(`/admin/drivers/${id}`, payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDriverUpdated');
      queryClient.invalidateQueries({ queryKey: DRIVERS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDriverUpdateFailed');
    },
  });
}

export function useToggleDriverStatus() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, isRegistered, reason }: { id: string; isRegistered: boolean; reason?: string }) => {
      const body: DriverStatusBody = { isRegistered };
      if (reason) body.reason = reason;
      const res = await api.patch<Driver>(`/admin/drivers/${id}/status`, body);
      return res.data;
    },
    onSuccess: (_, variables) => {
      if (variables.isRegistered === false) {
        showSuccessToast('successDriverDeactivated');
      } else {
        showSuccessToast('successDriverActivated');
      }
      queryClient.invalidateQueries({ queryKey: DRIVERS_QUERY_KEY });
    },
    onError: (err: unknown, variables) => {
      const isSuspend = variables.isRegistered === false;
      showErrorToast(
        err,
        isSuspend ? 'errorDriverSuspendFailed' : 'errorDriverUpdateFailed'
      );
    },
  });
}

export function useCancelDriverInvite() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, reason }: { id: string; reason?: string }) => {
      const body: CancelInviteBody = {};
      if (reason) body.reason = reason;
      await api.delete(`/admin/drivers/${id}`, { data: body });
    },
    onSuccess: () => {
      showSuccessToast('successDriverInviteCancelled');
      queryClient.invalidateQueries({ queryKey: DRIVERS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDriverCancelInviteFailed');
    },
  });
}

export function useResendDriverInvite() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.post<{ expiresAt: string; status: string }>(
        `/admin/drivers/${id}/resend-invite`
      );
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDriverInviteResent');
      queryClient.invalidateQueries({ queryKey: DRIVERS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDriverInviteResendFailed');
    },
  });
}

export function useForceLogoutDriver() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.post<void>(`/admin/drivers/${id}/logout`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDriverForceLogout');
      queryClient.invalidateQueries({ queryKey: DRIVERS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDriverForceLogoutFailed');
    },
  });
}

export function useImportDrivers() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (formData: FormData) => {
      const res = await api.post<void>('/admin/drivers/import', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDriversImported');
      queryClient.invalidateQueries({ queryKey: DRIVERS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorImportFailed');
    },
  });
}
