import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import type { Driver } from '@/types';

export const VEHICLES_QUERY_KEY = ['vehicles'] as const;
export const FLEET_DRIVERS_QUERY_KEY = ['fleet_drivers'] as const;

export type VehicleType = 'TRUCK' | 'VAN' | 'CAR' | 'MOTO';

export interface VehicleItem {
  id: string;
  make: string;
  model: string;
  manufactureYear?: number;
  color?: string;
  vin?: string;
  fuelType?: string;
  payloadKg?: number;
  volumeM3?: number;
  mileageKm?: number;
  plate: string;
  type: VehicleType;
  imageUrl?: string;
  driverId?: string;
  assigned?: boolean;
  active: boolean;
}

export interface VehiclePayload {
  make: string;
  model: string;
  manufactureYear?: number;
  color?: string;
  vin?: string;
  fuelType?: string;
  payloadKg?: number;
  volumeM3?: number | null;
  mileageKm?: number | null;
  plate: string;
  type: VehicleType;
  active: boolean;
  imageBase64?: string;
}

export function useVehicles() {
  return useQuery<VehicleItem[]>({
    queryKey: VEHICLES_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<VehicleItem[]>('/api/admin/vehicles');
      return Array.isArray(res.data) ? res.data : [];
    },
    retry: 1,
    staleTime: 30000,
  });
}

export function useFleetDrivers() {
  return useQuery<Driver[]>({
    queryKey: FLEET_DRIVERS_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<Driver[]>('/api/admin/fleet/drivers');
      return Array.isArray(res.data) ? res.data : [];
    },
    retry: 1,
    staleTime: 30000,
  });
}

export function useCreateVehicle() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: VehiclePayload) => {
      const res = await api.post<VehicleItem>('/api/admin/vehicles', payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successVehicleCreated');
      queryClient.invalidateQueries({ queryKey: VEHICLES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorVehicleCreateFailed');
    }
  });
}

export function useUpdateVehicle() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: VehiclePayload }) => {
      const res = await api.put<VehicleItem>(`/api/admin/vehicles/${id}`, payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successVehicleUpdated');
      queryClient.invalidateQueries({ queryKey: VEHICLES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorVehicleUpdateFailed');
    }
  });
}

export function useDeleteVehicle() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.delete<void>(`/api/admin/vehicles/${id}`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successVehicleDeleted');
      queryClient.invalidateQueries({ queryKey: VEHICLES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorVehicleDeleteFailed');
    }
  });
}

export function useReactivateVehicle() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.patch<VehicleItem>(`/api/admin/vehicles/${id}/reactivate`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successVehicleReactivated');
      queryClient.invalidateQueries({ queryKey: VEHICLES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorVehicleReactivateFailed');
    }
  });
}
